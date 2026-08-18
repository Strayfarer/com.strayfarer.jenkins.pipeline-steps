package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import hudson.Extension;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;

/** Adds the default-file invocation form which Pipeline's standard step parser cannot represent. */
@Extension
public final class WithEnvFileGlobalVariable extends GlobalVariable {

    @Override
    public @NonNull String getName() {
        return "withEnvFile";
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
            return invoke(".env", body);
        }

        @Whitelisted
        public Object call(String file, Closure<?> body) {
            return invoke(file, body);
        }

        private Object invoke(String file, Closure<?> body) {
            GroovyObject steps = (GroovyObject) script.getBinding().getVariable("steps");
            return steps.invokeMethod("withEnvFile", new Object[] {Map.of("file", file), body});
        }
    }
}
