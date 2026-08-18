package com.strayfarer.jenkins.pipelinesteps;

import com.cloudbees.groovy.cps.Block;
import com.cloudbees.groovy.cps.Builder;
import com.cloudbees.groovy.cps.Envs;
import com.cloudbees.groovy.cps.MethodLocation;
import com.cloudbees.groovy.cps.sandbox.Trusted;
import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import hudson.AbortException;
import hudson.Extension;
import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.cps.CpsClosure2;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/** Adds positional invocation forms which Pipeline's standard step parser cannot represent. */
@Extension
public final class EveryNodeGlobalVariable extends GlobalVariable {

    @Override
    public @NonNull String getName() {
        return "everyNode";
    }

    @Override
    public @NonNull Object getValue(@NonNull CpsScript script) {
        return new Call(script);
    }

    private static final class Call implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private final CpsScript script;

        private Call(CpsScript script) {
            this.script = script;
        }

        @Whitelisted
        public Object call(Closure<?> body) throws AbortException {
            return invoke(Map.of(), body);
        }

        @Whitelisted
        public Object call(Object label, Closure<?> body) throws AbortException {
            return invoke(Map.of("label", label), body);
        }

        @Whitelisted
        public Object call(Object label, boolean parallel, Closure<?> body) throws AbortException {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("label", label);
            arguments.put("parallel", parallel);
            return invoke(arguments, body);
        }

        @Whitelisted
        public Object call(Map<?, ?> arguments, Closure<?> body) throws AbortException {
            return invoke(arguments, body);
        }

        private Object invoke(Map<?, ?> arguments, Closure<?> body) throws AbortException {
            GroovyObject steps = (GroovyObject) script.getBinding().getVariable("steps");
            if (Boolean.TRUE.equals(arguments.get("parallel"))) {
                return invokeParallel(steps, arguments, body);
            }
            return steps.invokeMethod("everyNode", new Object[] {arguments, stagedBody(script, steps, body)});
        }

        private Object invokeParallel(GroovyObject steps, Map<?, ?> arguments, Closure<?> body) throws AbortException {
            Object requestedLabel = arguments.get("label");
            String label = requestedLabel == null ? null : requestedLabel.toString();
            List<EveryNodeStep.Target> targets = EveryNodeStep.snapshot(label);
            if (targets.isEmpty()) {
                throw new AbortException(
                        label == null
                                ? "No online Jenkins nodes are available"
                                : "No online Jenkins nodes match label '" + label + "'");
            }
            Map<String, Closure<?>> branches = new LinkedHashMap<>();
            for (EveryNodeStep.Target target : targets) {
                branches.put(target.name(), branchBody(script, steps, target, body));
            }
            Map<Object, Object> stepArguments = new LinkedHashMap<>(arguments);
            stepArguments.put("branches", branches);
            return steps.invokeMethod("everyNode", new Object[] {stepArguments, stagedBody(script, steps, body)});
        }
    }

    private static Closure<?> branchBody(
            CpsScript script, GroovyObject steps, EveryNodeStep.Target target, Closure<?> body) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("label", target.expression());
        arguments.put("nodeName", target.name());
        arguments.put("nodeExpression", target.expression());

        Builder builder = builder("parallelBranch");
        Block exactBody = builder.closure(1, List.of(), List.of(), stagedBlock(builder, script, steps, body));
        Block exactCall =
                builder.functionCall(1, builder.constant(steps), "everyNode", builder.constant(arguments), exactBody);
        return new CpsClosure2(script, script, List.of(), exactCall, Envs.empty());
    }

    private static Closure<?> stagedBody(CpsScript script, GroovyObject steps, Closure<?> body) {
        Builder builder = builder("stageBody");
        return new CpsClosure2(script, script, List.of(), stagedBlock(builder, script, steps, body), Envs.empty());
    }

    private static Block stagedBlock(Builder builder, CpsScript script, GroovyObject steps, Closure<?> body) {
        Block nodeName = builder.property(1, builder.property(1, builder.constant(script), "env"), "NODE_NAME");
        Block invokeBody = builder.functionCall(1, builder.constant(body), "call");
        Block stageBody = builder.closure(1, List.of(), List.of(), invokeBody);
        return builder.functionCall(1, builder.constant(steps), "stage", nodeName, stageBody);
    }

    private static Builder builder(String methodName) {
        return new Builder(new MethodLocation(EveryNodeGlobalVariable.class, methodName))
                .contextualize(Trusted.INSTANCE);
    }
}
