package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.Node;
import hudson.model.Run;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.CpsThread;
import org.jenkinsci.plugins.workflow.cps.EnvActionImpl;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.steps.MissingContextVariableException;

/** Provides an invisible Windows-node check without adding a Pipeline step node. */
@Extension
public final class IsWindowsGlobalVariable extends GlobalVariable {

    @Override
    public @NonNull String getName() {
        return "isWindows";
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
        public boolean call() throws IOException, MissingContextVariableException {
            Run<?, ?> run = script.$build();
            String nodeName = run == null ? null : EnvActionImpl.forRun(run).getProperty("NODE_NAME");
            if (nodeName == null) {
                throw new MissingContextVariableException(Launcher.class, null);
            }

            Jenkins jenkins = Jenkins.get();
            Node node = nodeName.equals(jenkins.getSelfLabel().getName()) ? jenkins : jenkins.getNode(nodeName);
            if (node == null) {
                throw new MissingContextVariableException(Launcher.class, null);
            }

            return !node.createLauncher(
                            CpsThread.current().getExecution().getOwner().getListener())
                    .isUnix();
        }
    }
}
