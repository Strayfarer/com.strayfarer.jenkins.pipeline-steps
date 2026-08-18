package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/** Applies environment variables parsed from a dotenv file to a Pipeline body. */
public final class WithEnvFileStep extends Step {

    private final String file;

    @DataBoundConstructor
    public WithEnvFileStep(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        this.file = file;
    }

    @SuppressWarnings("unused") // Jenkins databinding reads this property reflectively.
    public String getFile() {
        return file;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, file);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @NonNull
        @Override
        public String getDisplayName() {
            return "Set environment variables from a dotenv file";
        }

        @Override
        public String getFunctionName() {
            return "withEnvFile";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(FilePath.class, FlowNode.class);
        }

        @Override
        public String argumentsToString(Map<String, Object> namedArgs) {
            Object value = namedArgs.get("file");
            return value instanceof String ? (String) value : null;
        }
    }

    private static final class Execution extends GeneralNonBlockingStepExecution {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String file;

        private Execution(StepContext context, String file) {
            super(context);
            this.file = file;
        }

        @Override
        public boolean start() {
            run(() -> {
                FilePath workspace = getContext().get(FilePath.class);
                String contents;
                try (InputStream stream = workspace.child(file).read()) {
                    contents = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                Map<String, String> values = DotEnvParser.parse(contents);
                FlowNode node = getContext().get(FlowNode.class);
                node.addAction(new LabelAction(stageLogLabel(file, values)));
                EnvironmentExpander current = getContext().get(EnvironmentExpander.class);
                getContext()
                        .newBodyInvoker()
                        .withContext(EnvironmentExpander.merge(current, new DotEnvExpander(values)))
                        .withCallback(BodyExecutionCallback.wrap(getContext()))
                        .start();
            });
            return false;
        }
    }

    private static final class DotEnvExpander extends EnvironmentExpander {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Map<String, String> values;

        private DotEnvExpander(Map<String, String> values) {
            this.values = new LinkedHashMap<>(values);
        }

        @Override
        public void expand(@NonNull EnvVars environment) throws IOException, InterruptedException {
            environment.putAll(values);
        }
    }

    static String stageLogLabel(String file, Map<String, String> values) {
        String assignments = values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + escape(entry.getValue()))
                .collect(Collectors.joining(", "));
        return values.isEmpty()
                ? "withEnvFile (" + file + "): no variables"
                : "withEnvFile (" + file + "): " + assignments;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
