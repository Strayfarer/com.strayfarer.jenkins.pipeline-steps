package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundSetter;

abstract class AbstractCommandStep extends Step {

    enum ResultMode {
        NONE,
        STATUS,
        STDOUT
    }

    private final String script;
    private boolean echoScript;
    private String encoding = "UTF-8";

    AbstractCommandStep(String script) {
        if (script == null) {
            throw new IllegalArgumentException("script is required");
        }
        this.script = script;
    }

    public final String getScript() {
        return script;
    }

    public final boolean isEchoScript() {
        return echoScript;
    }

    @DataBoundSetter
    public final void setEchoScript(boolean echoScript) {
        this.echoScript = echoScript;
    }

    public final String getEncoding() {
        return encoding;
    }

    @DataBoundSetter
    public final void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    abstract ResultMode resultMode();

    @Override
    public final StepExecution start(StepContext context) throws Exception {
        if (echoScript) {
            context.get(TaskListener.class).getLogger().println(script);
        }

        ResultMode mode = resultMode();
        Launcher launcher = context.get(Launcher.class);
        EnvVars environment = context.get(EnvVars.class);
        DockerContext docker = context.get(DockerContext.class);
        DurableTask task = docker == null
                ? CommandTaskFactory.nativeTask(script, launcher, environment, mode == ResultMode.STDOUT)
                : DockerCommandTaskFactory.task(
                        docker, script, context.get(FilePath.class), launcher, environment, mode == ResultMode.STDOUT);
        DurableTaskStepAdapter taskStep = new DurableTaskStepAdapter(task);
        taskStep.setEncoding(encoding);
        taskStep.setReturnStatus(mode == ResultMode.STATUS);
        taskStep.setReturnStdout(mode == ResultMode.STDOUT);
        StepContext resultContext = mode == ResultMode.STDOUT ? new TrimmedOutputContext(context) : context;
        return taskStep.start(resultContext);
    }

    abstract static class Descriptor extends StepDescriptor {

        @Override
        public final Set<? extends Class<?>> getRequiredContext() {
            return Set.of(FilePath.class, EnvVars.class, Launcher.class, TaskListener.class);
        }

        @Override
        public final String argumentsToString(Map<String, Object> namedArgs) {
            Object script = namedArgs.get("script");
            return script instanceof String ? (String) script : null;
        }
    }

    private static final class TrimmedOutputContext extends ForwardingStepContext {

        private static final long serialVersionUID = 1L;

        private TrimmedOutputContext(StepContext delegate) {
            super(delegate);
        }

        @Override
        public void onSuccess(Object result) {
            delegate.onSuccess(result instanceof String ? ((String) result).trim() : result);
        }
    }
}
