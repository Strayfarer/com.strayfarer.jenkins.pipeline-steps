package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
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

    private record Target(String name, String expression) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class Execution extends StepExecution {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String label;
        private final boolean parallel;
        private List<Target> targets;
        private List<Target> remaining;
        private List<NodeQueueTask> tasks;
        private List<Boolean> active;
        private BodyExecution inPlaceBody;
        private volatile int inPlaceIndex = -1;
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
                throw new AbortException(
                        label == null
                                ? "No online Jenkins nodes are available"
                                : "No online Jenkins nodes match label '" + label + "'");
            }
            Node currentNode = getContext().get(Node.class);
            String currentNodeName =
                    currentNode == null ? null : currentNode.getSelfLabel().getName();
            int currentIndex = indexOf(selected, currentNodeName);
            if (currentIndex > 0) {
                List<Target> reordered = new ArrayList<>(selected);
                reordered.add(0, reordered.remove(currentIndex));
                selected = List.copyOf(reordered);
            }
            int initialSequential = -1;
            synchronized (this) {
                targets = selected;
                remaining = new ArrayList<>(selected);
                tasks = new ArrayList<>();
                active = new ArrayList<>();
                for (int index = 0; index < selected.size(); index++) {
                    tasks.add(null);
                    active.add(false);
                }
                inPlaceIndex = currentIndex >= 0 ? 0 : -1;
                if (parallel) {
                    next = selected.size();
                } else {
                    initialSequential = next++;
                }
            }
            if (parallel) {
                int index = 0;
                if (inPlaceIndex == 0) {
                    launchInPlace(0);
                    index++;
                }
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
                    cancelInPlace(exception);
                }
            } else if (inPlaceIndex == 0) {
                launchInPlace(initialSequential);
            } else {
                launch(initialSequential);
            }
            return false;
        }

        @Override
        public void stop(@NonNull Throwable cause) {
            List<NodeQueueTask> activeTasks;
            synchronized (this) {
                if (complete) {
                    return;
                }
                failure = cause;
                activeTasks = tasks == null
                        ? List.of()
                        : tasks.stream().filter(Objects::nonNull).toList();
            }
            for (NodeQueueTask task : activeTasks) {
                task.cancel(cause);
            }
            cancelInPlace(cause);
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
            NodeContext context = new NodeContext(getContext(), this, index);
            List<Target> candidates = parallel ? List.of(targets.get(index)) : List.copyOf(remaining);
            NodeQueueTask task = new NodeQueueTask(
                    context,
                    candidates.stream().map(Target::name).toList(),
                    candidates.stream().map(Target::expression).toList());
            tasks.set(index, task);
            active.set(index, true);
            Queue.WaitingItem item = task.schedule();
            if (item == null) {
                throw new AbortException("Jenkins queue refused nodes "
                        + candidates.stream().map(Target::name).toList());
            }
        }

        private synchronized void launchInPlace(int index) {
            Target target = targets.get(index);
            active.set(index, true);
            inPlaceBody = getContext()
                    .newBodyInvoker()
                    .withDisplayName(target.name())
                    .withCallback(new InPlaceCallback(this, index, target.name()))
                    .start();
        }

        private void childSucceeded(int index, Object result) {
            int following = -1;
            boolean reportSuccess = false;
            Throwable reportedFailure = null;
            synchronized (this) {
                if (complete || !active.get(index)) {
                    return;
                }
                active.set(index, false);
                tasks.set(index, null);
                if (index == inPlaceIndex) {
                    inPlaceBody = null;
                }
                finished++;
                if (!parallel && !removeSelectedTarget(result)) {
                    failure = new IOException("everyNode completed on an unsnapshotted node: " + result);
                }
                if (!parallel && failure == null && !remaining.isEmpty()) {
                    following = next++;
                } else if ((parallel && finished == targets.size())
                        || (!parallel && (remaining.isEmpty() || failure != null))) {
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

        private boolean removeSelectedTarget(Object result) {
            if (!(result instanceof String selectedName)) {
                return false;
            }
            Iterator<Target> iterator = remaining.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().name().equals(selectedName)) {
                    iterator.remove();
                    return true;
                }
            }
            return false;
        }

        private void childFailed(int index, Throwable cause) {
            Throwable reportedFailure = null;
            synchronized (this) {
                if (complete) {
                    return;
                }
                if (active.get(index)) {
                    active.set(index, false);
                    tasks.set(index, null);
                    if (index == inPlaceIndex) {
                        inPlaceBody = null;
                    }
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

        private void cancelInPlace(Throwable cause) {
            BodyExecution body;
            synchronized (this) {
                body = inPlaceBody;
            }
            if (body != null) {
                body.cancel(cause);
            }
        }

        private static int indexOf(List<Target> targets, String nodeName) {
            if (nodeName == null) {
                return -1;
            }
            for (int index = 0; index < targets.size(); index++) {
                if (targets.get(index).name().equals(nodeName)) {
                    return index;
                }
            }
            return -1;
        }

        private static List<Target> snapshot(String expression) {
            Label parsed = expression == null ? null : Label.parseExpression(expression);
            Jenkins jenkins = Jenkins.get();
            List<Node> nodes = new ArrayList<>(jenkins.getNodes());
            if (jenkins.getNumExecutors() > 0) {
                nodes.add(jenkins);
            }
            return nodes.stream()
                    .filter(node -> parsed == null || parsed.matches(node))
                    .filter(node -> {
                        Computer computer = node.toComputer();
                        return computer != null && computer.isOnline();
                    })
                    .map(node -> new Target(
                            node.getSelfLabel().getName(), node.getSelfLabel().getExpression()))
                    .sorted(Comparator.comparing(Target::name))
                    .toList();
        }
    }

    private static final class InPlaceCallback extends BodyExecutionCallback {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Execution owner;
        private final int index;
        private final String nodeName;
        private Throwable stageFailure;

        private InPlaceCallback(Execution owner, int index, String nodeName) {
            this.owner = owner;
            this.index = index;
            this.nodeName = nodeName;
        }

        @Override
        public void onStart(StepContext context) {
            stageFailure = EveryNodeStageAction.attach(context, nodeName);
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            if (stageFailure == null) {
                owner.childSucceeded(index, nodeName);
            } else {
                owner.childFailed(index, stageFailure);
            }
        }

        @Override
        public void onFailure(StepContext context, Throwable failure) {
            if (stageFailure != null) {
                failure.addSuppressed(stageFailure);
            }
            owner.childFailed(index, failure);
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
            owner.childSucceeded(index, result);
        }

        @Override
        public void onFailure(@NonNull Throwable failure) {
            owner.childFailed(index, failure);
        }
    }
}
