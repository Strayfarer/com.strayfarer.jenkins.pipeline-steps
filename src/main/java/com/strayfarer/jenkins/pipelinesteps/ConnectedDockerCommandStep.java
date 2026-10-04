package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Executes a command through a connected Docker container handle. */
public final class ConnectedDockerCommandStep extends AbstractCommandStep {

    private final String container;
    private final ResultMode mode;
    private Map<String, String> label = Map.of();
    private List<String> environment = List.of();

    @DataBoundConstructor
    public ConnectedDockerCommandStep(String script, String container, String mode) {
        super(script);
        this.container = container;
        this.mode = ResultMode.valueOf(mode);
    }

    public String getContainer() {
        return container;
    }

    public String getMode() {
        return mode.name();
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
    ResultMode resultMode() {
        return mode;
    }

    @Override
    DockerContext dockerContext(StepContext context) {
        return new DockerContext(container, label, environment);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Execute command through a connected Docker container";
        }

        @Override
        public String getFunctionName() {
            return "connectedDockerCommand";
        }
    }
}
