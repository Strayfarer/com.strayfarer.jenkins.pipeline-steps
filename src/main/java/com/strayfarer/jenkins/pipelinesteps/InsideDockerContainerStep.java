package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Selects an existing Docker container for nested command steps. */
public final class InsideDockerContainerStep extends Step {

    private static final String INSPECT_CONTAINER = "PIPELINE_INTERNAL_DOCKER_INSPECT_CONTAINER";
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String container;
    private List<String> environment = List.of();

    @DataBoundConstructor
    public InsideDockerContainerStep(String container) {
        if (container == null || container.isBlank()) {
            throw new IllegalArgumentException("container is required");
        }
        this.container = container;
    }

    @SuppressWarnings("unused") // Jenkins databinding reads this property reflectively.
    public String getContainer() {
        return container;
    }

    public List<String> getEnvironment() {
        return environment;
    }

    @DataBoundSetter
    public void setEnvironment(List<String> environment) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (environment != null) {
            for (String entry : environment) {
                String name = entry == null ? "" : entry.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid environment variable name: " + name);
                }
                normalized.add(name);
            }
        }
        this.environment = List.copyOf(normalized);
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        Launcher launcher = context.get(Launcher.class);
        String inspectionScript = launcher.isUnix()
                ? "output=$(docker inspect --type container --format '{{.Id}} {{.State.Running}} {{.Platform}}' -- \"$"
                        + INSPECT_CONTAINER
                        + "\")\nstatus=$?\nprintf '%s\\n%s\\n' \"$status\" \"$output\"\nexit 0"
                : "$output = & docker inspect --type container --format '{{.Id}} {{.State.Running}} {{.Platform}}' -- $env:"
                        + INSPECT_CONTAINER
                        + "\r\n$status = $LASTEXITCODE\r\nWrite-Output $status\r\n$output\r\nexit 0";
        DurableTask task = CommandTaskFactory.nativeTask(inspectionScript, launcher, context.get(EnvVars.class), false);
        task = new EnvironmentOverlayDurableTask(task, Map.of(INSPECT_CONTAINER, container));
        DurableTaskStepAdapter taskStep = new DurableTaskStepAdapter(task);
        taskStep.setEncoding("UTF-8");
        taskStep.setReturnStdout(true);
        return taskStep.start(new InspectionContext(context, container, environment));
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run commands inside an existing Docker container";
        }

        @Override
        public String getFunctionName() {
            return "insideDockerContainer";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(FilePath.class, EnvVars.class, Launcher.class, TaskListener.class);
        }
    }

    private static final class InspectionContext extends ForwardingStepContext {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String container;
        private final List<String> environment;

        private InspectionContext(StepContext delegate, String container, List<String> environment) {
            super(delegate);
            this.container = container;
            this.environment = new ArrayList<>(environment);
        }

        @Override
        public void onSuccess(Object result) {
            try {
                DockerContext docker = DockerContext.fromInspection(container, environment, (String) result);
                EnvironmentExpander current = delegate.get(EnvironmentExpander.class);
                EnvironmentExpander metadata = EnvironmentExpander.constant(Map.of(
                        "PIPELINE_DOCKER_CONTAINER_NAME", docker.container(),
                        "PIPELINE_DOCKER_CONTAINER_ID", docker.id(),
                        "PIPELINE_DOCKER_CONTAINER_OS", docker.os()));
                delegate.newBodyInvoker()
                        .withContext(docker)
                        .withContext(EnvironmentExpander.merge(current, metadata))
                        .withCallback(BodyExecutionCallback.wrap(delegate))
                        .start();
            } catch (Exception exception) {
                delegate.onFailure(exception);
            }
        }
    }
}
