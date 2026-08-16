package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/** Streams a command's output and returns its exit status. */
public final class ExecStatusStep extends AbstractCommandStep {

    @DataBoundConstructor
    public ExecStatusStep(String script) {
        super(script);
    }

    @Override
    ResultMode resultMode() {
        return ResultMode.STATUS;
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Execute command and return status";
        }

        @Override
        public String getFunctionName() {
            return "execStatus";
        }
    }
}
