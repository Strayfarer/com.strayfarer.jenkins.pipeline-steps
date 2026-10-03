package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.io.Serial;
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

    DockerContext dockerContext(StepContext context) throws Exception {
        return context.get(DockerContext.class);
    }

    @Override
    public final StepExecution start(StepContext context) throws Exception {
        if (echoScript) {
            context.get(TaskListener.class).getLogger().println(script);
        }

        ResultMode mode = resultMode();
        DockerContext docker = dockerContext(context);
        if (docker == null) {
            return commandExecution(context, script, encoding, mode, null, null);
        }

        return new DockerCommandExecution(context, docker, script, encoding, mode);
    }

    private static final class DockerCommandExecution extends StepExecution {

        @Serial
        private static final long serialVersionUID = 1L;

        private final DockerContext docker;
        private final String script;
        private final String encoding;
        private final ResultMode mode;
        private StepExecution active;

        private DockerCommandExecution(
                StepContext context, DockerContext docker, String script, String encoding, ResultMode mode) {
            super(context);
            this.docker = docker;
            this.script = script;
            this.encoding = encoding;
            this.mode = mode;
        }

        @Override
        public boolean start() throws Exception {
            StepContext context = getContext();
            Launcher launcher = context.get(Launcher.class);
            String inspectionScript = launcher.isUnix()
                    ? "output=$(docker inspect --type container --format '{{.State.Running}} {{.Platform}}' -- \"$PIPELINE_INTERNAL_DOCKER_INSPECT_CONTAINER\")\nstatus=$?\nprintf '%s\\n%s\\n' \"$status\" \"$output\"\nexit 0"
                    : "$output = & docker inspect --type container --format '{{.State.Running}} {{.Platform}}' -- $env:PIPELINE_INTERNAL_DOCKER_INSPECT_CONTAINER\r\n$status = $LASTEXITCODE\r\nWrite-Output $status\r\n$output\r\nexit 0";
            DurableTask inspection = CommandTaskFactory.nativeTask(
                    inspectionScript, context.get(FilePath.class), launcher, context.get(EnvVars.class), false);
            inspection = new EnvironmentOverlayDurableTask(
                    inspection, Map.of("PIPELINE_INTERNAL_DOCKER_INSPECT_CONTAINER", docker.container()));
            DurableTaskStepAdapter inspectionStep = new DurableTaskStepAdapter(inspection);
            inspectionStep.setEncoding("UTF-8");
            inspectionStep.setReturnStdout(true);
            active = inspectionStep.start(new InspectionContext(context, this));
            active.start();
            return false;
        }

        private void startCommand(String inspectionOutput) throws Exception {
            String os = DockerContext.osFromInspection(docker.container(), inspectionOutput);
            active = commandExecution(getContext(), script, encoding, mode, docker, os);
            active.start();
        }

        @Override
        public void onResume() {
            if (active != null) {
                active.onResume();
            }
        }

        @Override
        public void stop(Throwable cause) throws Exception {
            if (active != null) {
                active.stop(cause);
            }
        }
    }

    private static StepExecution commandExecution(
            StepContext context, String script, String encoding, ResultMode mode, DockerContext docker, String os)
            throws Exception {
        FilePath workspace = context.get(FilePath.class);
        Launcher launcher = context.get(Launcher.class);
        EnvVars environment = context.get(EnvVars.class);
        DurableTask task = docker == null
                ? CommandTaskFactory.nativeTask(script, workspace, launcher, environment, mode == ResultMode.STDOUT)
                : DockerCommandTaskFactory.task(
                        docker, os, script, workspace, launcher, environment, mode == ResultMode.STDOUT);
        DurableTaskStepAdapter taskStep = new DurableTaskStepAdapter(task);
        taskStep.setEncoding(encoding);
        taskStep.setReturnStatus(mode == ResultMode.STATUS);
        taskStep.setReturnStdout(mode == ResultMode.STDOUT);
        StepContext resultContext = mode == ResultMode.STDOUT ? new TrimmedOutputContext(context) : context;
        return taskStep.start(resultContext);
    }

    private static final class InspectionContext extends ForwardingStepContext {

        @Serial
        private static final long serialVersionUID = 1L;

        private final DockerCommandExecution execution;

        private InspectionContext(StepContext delegate, DockerCommandExecution execution) {
            super(delegate);
            this.execution = execution;
        }

        @Override
        public void onSuccess(Object result) {
            try {
                execution.startCommand((String) result);
            } catch (Exception exception) {
                delegate.onFailure(exception);
            }
        }
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

        @Serial
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
