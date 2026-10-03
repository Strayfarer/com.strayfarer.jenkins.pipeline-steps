package com.strayfarer.jenkins.pipelinesteps;

import com.cloudbees.groovy.cps.Block;
import com.cloudbees.groovy.cps.Builder;
import com.cloudbees.groovy.cps.Envs;
import com.cloudbees.groovy.cps.MethodLocation;
import com.cloudbees.groovy.cps.sandbox.Trusted;
import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.GroovyObject;
import hudson.Extension;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.cps.CpsClosure2;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/** Creates a serializable handle for commands in a named Docker container. */
@Extension
public final class ConnectToDockerContainerGlobalVariable extends GlobalVariable {

    @Override
    public @NonNull String getName() {
        return "connectToDockerContainer";
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
        public Map<String, CpsClosure2> call(String container) {
            return handle(new DockerContext(container, List.of()));
        }

        @Whitelisted
        public Map<String, CpsClosure2> call(Map<?, ?> arguments) {
            Object container = arguments.get("container");
            if (!(container instanceof String)) {
                throw new IllegalArgumentException("container is required");
            }
            Object environment = arguments.get("environment");
            if (environment != null && !(environment instanceof List<?>)) {
                throw new IllegalArgumentException("environment must be a list of names");
            }
            List<String> names = new ArrayList<>();
            if (environment != null) {
                List<?> values = (List<?>) environment;
                for (Object value : values) {
                    if (value != null && !(value instanceof String)) {
                        throw new IllegalArgumentException("environment must be a list of names");
                    }
                    names.add((String) value);
                }
            }
            return handle(new DockerContext((String) container, names));
        }

        private Map<String, CpsClosure2> handle(DockerContext docker) {
            GroovyObject steps = (GroovyObject) script.getBinding().getVariable("steps");
            Map<String, CpsClosure2> methods = new LinkedHashMap<>();
            methods.put("exec", commandClosure(script, steps, docker, "NONE"));
            methods.put("execStatus", commandClosure(script, steps, docker, "STATUS"));
            methods.put("execStdout", commandClosure(script, steps, docker, "STDOUT"));
            return methods;
        }
    }

    private static CpsClosure2 commandClosure(CpsScript script, GroovyObject steps, DockerContext docker, String mode) {
        Builder builder = new Builder(new MethodLocation(ConnectToDockerContainerGlobalVariable.class, mode))
                .contextualize(Trusted.INSTANCE);
        Block arguments = builder.staticCall(
                1,
                ConnectToDockerContainerGlobalVariable.class,
                "commandArguments",
                builder.localVariable("options"),
                builder.constant(docker),
                builder.constant(mode));
        Block command = builder.functionCall(1, builder.constant(steps), "connectedDockerCommand", arguments);
        return new CpsClosure2(script, script, List.of("options"), command, Envs.empty());
    }

    @Whitelisted
    public static Map<String, Object> commandArguments(Object options, DockerContext docker, String mode) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (options instanceof String command) {
            arguments.put("script", command);
        } else if (options instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || !(key.equals("script") || key.equals("echoScript") || key.equals("encoding"))) {
                    throw new IllegalArgumentException("Unsupported command option: " + entry.getKey());
                }
                arguments.put(key, entry.getValue());
            }
        } else {
            throw new IllegalArgumentException("Expected a command string or option map");
        }
        arguments.put("container", docker.container());
        arguments.put("environment", docker.environment());
        arguments.put("mode", mode);
        return arguments;
    }
}
