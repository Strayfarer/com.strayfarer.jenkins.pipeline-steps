package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Executes a Pipeline body on a snapshot of matching online Jenkins nodes. */
public final class EveryNodeStep extends Step {

    private final String label;
    private boolean parallel;

    @DataBoundConstructor
    public EveryNodeStep(String label) {
        if (label == null) {
            throw new IllegalArgumentException("label is required");
        }
        this.label = label;
    }

    @SuppressWarnings("unused") // Jenkins databinding reads this property reflectively.
    public String getLabel() {
        return label;
    }

    @SuppressWarnings("unused") // Jenkins databinding reads this property reflectively.
    public boolean isParallel() {
        return parallel;
    }

    @DataBoundSetter
    public void setParallel(boolean parallel) {
        this.parallel = parallel;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, label, parallel);
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "everyNode";
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Execute on every matching node";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of();
        }

        @Override
        public String argumentsToString(Map<String, Object> namedArgs) {
            Object value = namedArgs.get("label");
            return value instanceof String ? (String) value : null;
        }
    }

    private record Target(String name, String selfLabel) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class Execution extends StepExecution {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String label;
        private final boolean parallel;
        private List<Target> targets;
        private List<NodeQueueTask> tasks;
        private int next;
        private int finished;
        private Throwable failure;
        private boolean complete;

        private Execution(StepContext context, String label, boolean parallel) {
            super(context);
            this.label = label;
            this.parallel = parallel;
        }

        @Override
        public boolean start() throws Exception {
            List<Target> selected = snapshot(label);
            if (selected.isEmpty()) {
                throw new AbortException("No online Jenkins nodes match label '" + label + "'");
            }
            int initialSequential = -1;
            synchronized (this) {
                targets = selected;
                tasks = new ArrayList<>();
                for (int index = 0; index < selected.size(); index++) {
                    tasks.add(null);
                }
                if (parallel) {
                    next = selected.size();
                } else {
                    initialSequential = next++;
                }
            }
            if (parallel) {
                int index = 0;
                try {
                    for (; index < selected.size(); index++) {
                        launch(index);
                    }
                } catch (Exception exception) {
                    List<NodeQueueTask> active;
                    synchronized (this) {
                        failure = exception;
                        finished += selected.size() - index - 1;
                        active = tasks.stream().filter(Objects::nonNull).toList();
                    }
                    for (NodeQueueTask task : active) {
                        task.cancel(exception);
                    }
                }
            } else {
                launch(initialSequential);
            }
            return false;
        }

        @Override
        public void stop(@NonNull Throwable cause) {
            List<NodeQueueTask> active;
            synchronized (this) {
                if (complete) {
                    return;
                }
                failure = cause;
                active = tasks == null
                        ? List.of()
                        : tasks.stream().filter(Objects::nonNull).toList();
            }
            for (NodeQueueTask task : active) {
                task.cancel(cause);
            }
        }

        @Override
        public void onResume() {
            List<NodeQueueTask> active;
            synchronized (this) {
                active = complete || tasks == null
                        ? List.of()
                        : tasks.stream().filter(Objects::nonNull).toList();
            }
            for (NodeQueueTask task : active) {
                task.resume();
            }
        }

        @Override
        public synchronized String getStatus() {
            return complete ? "complete" : "finished " + finished + " of " + (targets == null ? 0 : targets.size());
        }

        private synchronized void launch(int index) throws Exception {
            Target target = targets.get(index);
            NodeContext context = new NodeContext(getContext(), this, index);
            NodeQueueTask task = new NodeQueueTask(context, target.name(), target.selfLabel());
            tasks.set(index, task);
            Queue.WaitingItem item = task.schedule();
            if (item == null) {
                throw new AbortException("Jenkins queue refused node '" + target.name() + "'");
            }
        }

        private void childSucceeded(int index) {
            int following = -1;
            boolean reportSuccess = false;
            Throwable reportedFailure = null;
            synchronized (this) {
                if (complete || tasks.get(index) == null) {
                    return;
                }
                tasks.set(index, null);
                finished++;
                if (!parallel && failure == null && next < targets.size()) {
                    following = next++;
                } else if (finished == targets.size() || (!parallel && failure != null)) {
                    complete = true;
                    reportSuccess = failure == null;
                    reportedFailure = failure;
                }
            }
            if (following >= 0) {
                try {
                    launch(following);
                } catch (Exception exception) {
                    childFailed(following, exception);
                }
            } else if (reportSuccess) {
                getContext().onSuccess(null);
            } else if (reportedFailure != null) {
                getContext().onFailure(reportedFailure);
            }
        }

        private void childFailed(int index, Throwable cause) {
            Throwable reportedFailure = null;
            synchronized (this) {
                if (complete) {
                    return;
                }
                if (tasks.get(index) != null) {
                    tasks.set(index, null);
                    finished++;
                }
                if (failure == null) {
                    failure = cause;
                } else if (failure != cause) {
                    failure.addSuppressed(cause);
                }
                if (!parallel || finished == targets.size()) {
                    complete = true;
                    reportedFailure = failure;
                }
            }
            if (reportedFailure != null) {
                getContext().onFailure(reportedFailure);
            }
        }

        private static List<Target> snapshot(String expression) {
            Label parsed = Label.parseExpression(expression);
            Jenkins jenkins = Jenkins.get();
            List<Node> nodes = new ArrayList<>(jenkins.getNodes());
            if (jenkins.getNumExecutors() > 0) {
                nodes.add(jenkins);
            }
            return nodes.stream()
                    .filter(parsed::matches)
                    .filter(node -> {
                        Computer computer = node.toComputer();
                        return computer != null && computer.isOnline();
                    })
                    .map(node -> new Target(
                            node.getSelfLabel().getName(), node.getSelfLabel().getName()))
                    .sorted(Comparator.comparing(Target::name))
                    .toList();
        }
    }

    private static final class NodeContext extends ForwardingStepContext {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Execution owner;
        private final int index;

        private NodeContext(StepContext delegate, Execution owner, int index) {
            super(delegate, index);
            this.owner = owner;
            this.index = index;
        }

        @Override
        public void onSuccess(Object result) {
            owner.childSucceeded(index);
        }

        @Override
        public void onFailure(@NonNull Throwable failure) {
            owner.childFailed(index, failure);
        }
    }
}
