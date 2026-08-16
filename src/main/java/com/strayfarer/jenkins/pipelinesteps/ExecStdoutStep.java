package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/** Streams a command's output and returns a trimmed copy of stdout. */
public final class ExecStdoutStep extends AbstractCommandStep {

    @DataBoundConstructor
    public ExecStdoutStep(String script) {
        super(script);
    }

    @Override
    ResultMode resultMode() {
        return ResultMode.STDOUT;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Execute command and return stdout";
        }

        @Override
        public String getFunctionName() {
            return "execStdout";
        }
    }
}
