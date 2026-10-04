package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.io.Serial;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Selects a Docker container for nested command steps. */
public final class InsideDockerContainerStep extends Step {

    private final String container;
    private Map<String, String> label = Map.of();
    private List<String> environment = List.of();

    @DataBoundConstructor
    public InsideDockerContainerStep(String container) {
        this.container = container;
    }

    @SuppressWarnings("unused") // Jenkins databinding reads this property reflectively.
    public String getContainer() {
        return container;
    }

    public List<String> getEnvironment() {
        return environment;
    }

    public Map<String, String> getLabel() {
        return label;
    }

    @DataBoundSetter
    public void setLabel(Map<String, String> label) {
        this.label = DockerContext.normalizeLabel(label);
    }

    @DataBoundSetter
    public void setEnvironment(List<String> environment) {
        this.environment = DockerContext.normalizeEnvironment(environment);
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, new DockerContext(container, label, environment));
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Select a Docker container for nested commands";
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
            return Set.of();
        }
    }

    private static final class Execution extends StepExecution {

        @Serial
        private static final long serialVersionUID = 1L;

        private final DockerContext docker;

        private Execution(StepContext context, DockerContext docker) {
            super(context);
            this.docker = docker;
        }

        @Override
        public boolean start() {
            StepContext context = getContext();
            context.newBodyInvoker()
                    .withContext(docker)
                    .withCallback(BodyExecutionCallback.wrap(context))
                    .start();
            return false;
        }
    }
}
