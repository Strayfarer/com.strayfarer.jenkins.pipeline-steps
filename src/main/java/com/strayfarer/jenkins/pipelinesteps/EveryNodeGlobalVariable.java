package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import hudson.Extension;
import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
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
        public Object call(Closure<?> body) {
            return invoke(Map.of(), body);
        }

        @Whitelisted
        public Object call(Object label, Closure<?> body) {
            return invoke(Map.of("label", label), body);
        }

        @Whitelisted
        public Object call(Object label, boolean parallel, Closure<?> body) {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("label", label);
            arguments.put("parallel", parallel);
            return invoke(arguments, body);
        }

        @Whitelisted
        public Object call(Map<?, ?> arguments, Closure<?> body) {
            return invoke(arguments, body);
        }

        private Object invoke(Map<?, ?> arguments, Closure<?> body) {
            GroovyObject steps = (GroovyObject) script.getBinding().getVariable("steps");
            return steps.invokeMethod("everyNode", new Object[] {arguments, body});
        }
    }
}
