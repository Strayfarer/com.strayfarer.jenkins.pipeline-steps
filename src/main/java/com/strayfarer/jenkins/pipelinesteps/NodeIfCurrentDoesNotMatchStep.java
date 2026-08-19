package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import java.io.Serial;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStep;
import org.kohsuke.stapler.DataBoundConstructor;

/** Reuses a matching current node or delegates to Jenkins' native node allocation. */
public final class NodeIfCurrentDoesNotMatchStep extends Step {

    private final String labelExpression;

    @DataBoundConstructor
    public NodeIfCurrentDoesNotMatchStep(String labelExpression) {
        this.labelExpression = labelExpression;
    }

    @SuppressWarnings("unused") // Jenkins databinding reads this property reflectively.
    public String getLabelExpression() {
        return labelExpression;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        Node currentNode = context.get(Node.class);
        String currentNodeName =
                currentNode == null ? null : currentNode.getSelfLabel().getName();
        boolean reuse = currentNodeName != null
                && (currentNodeName.equals(labelExpression)
                        || Label.parseExpression(labelExpression).matches(currentNode));
        TaskListener listener = context.get(TaskListener.class);
        if (reuse) {
            listener.getLogger()
                    .println("Reusing current Jenkins node '" + currentNodeName + "' for label '" + labelExpression
                            + "'");
            return new InPlaceExecution(context);
        }

        listener.getLogger().println("Requesting Jenkins node allocation for label '" + labelExpression + "'");
        return new ExecutorStep(labelExpression).start(context);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "nodeIfCurrentDoesNotMatch";
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Reuse the current node when it matches";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(TaskListener.class);
        }

        @Override
        public String argumentsToString(Map<String, Object> namedArgs) {
            Object value = namedArgs.get("labelExpression");
            return value instanceof String ? (String) value : null;
        }
    }

    private static final class InPlaceExecution extends StepExecution {

        @Serial
        private static final long serialVersionUID = 1L;

        private BodyExecution body;

        private InPlaceExecution(StepContext context) {
            super(context);
        }

        @Override
        public boolean start() {
            body = getContext()
                    .newBodyInvoker()
                    .withCallback(BodyExecutionCallback.wrap(getContext()))
                    .start();
            return false;
        }

        @Override
        public void stop(@NonNull Throwable cause) {
            BodyExecution running = body;
            if (running != null) {
                running.cancel(cause);
            }
        }
    }
}
