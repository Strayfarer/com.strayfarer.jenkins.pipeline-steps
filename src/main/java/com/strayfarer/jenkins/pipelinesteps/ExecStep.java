package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.PowershellScript;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Runs a command in the current agent's native shell. */
public final class ExecStep extends Step {

    private final String script;
    private boolean echoScript;
    private String encoding = "UTF-8";

    @DataBoundConstructor
    public ExecStep(String script) {
        if (script == null) {
            throw new IllegalArgumentException("script is required");
        }
        this.script = script;
    }

    public String getScript() {
        return script;
    }

    public boolean isEchoScript() {
        return echoScript;
    }

    @DataBoundSetter
    public void setEchoScript(boolean echoScript) {
        this.echoScript = echoScript;
    }

    public String getEncoding() {
        return encoding;
    }

    @DataBoundSetter
    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        if (echoScript) {
            context.get(TaskListener.class).getLogger().println(script);
        }
        NativeTaskStep taskStep =
                new NativeTaskStep(task(context.get(Launcher.class).isUnix()));
        taskStep.setEncoding(encoding);
        return taskStep.start(context);
    }

    DurableTask task(boolean isUnix) {
        if (isUnix) {
            return new BourneShellScript("#!/bin/sh\n" + script);
        }
        PowershellScript powershellScript = new PowershellScript(script);
        powershellScript.setPowershellBinary("pwsh");
        return powershellScript;
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Execute command";
        }

        @Override
        public String getFunctionName() {
            return "exec";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(FilePath.class, EnvVars.class, Launcher.class, TaskListener.class);
        }

        @Override
        public String argumentsToString(Map<String, Object> namedArgs) {
            Object script = namedArgs.get("script");
            return script instanceof String ? (String) script : null;
        }
    }

    private static final class NativeTaskStep extends DurableTaskStep {

        private final DurableTask task;

        private NativeTaskStep(DurableTask task) {
            this.task = task;
        }

        @Override
        protected DurableTask task() {
            return task;
        }
    }
}
