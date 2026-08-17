package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.SubTask;
import hudson.slaves.WorkspaceList;
import java.io.IOException;
import java.io.Serializable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;
import org.jenkinsci.plugins.durabletask.executors.ContinuableExecutable;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;

final class NodeQueueTask implements ContinuedTask, Serializable {

    private static final long serialVersionUID = 1L;

    private final String id = UUID.randomUUID().toString();
    private final StepContext context;
    private final String nodeName;
    private final String selfLabel;
    private final String runId;
    private volatile boolean started;
    private volatile boolean completed;
    private volatile BodyExecution body;
    private NodeExecutionContext nodeContext;

    NodeQueueTask(StepContext context, String nodeName, String selfLabel) throws IOException, InterruptedException {
        this.context = context;
        this.nodeName = nodeName;
        this.selfLabel = selfLabel;
        this.runId = context.get(Run.class).getExternalizableId();
    }

    @Override
    public Label getAssignedLabel() {
        return Label.get(selfLabel);
    }

    @Override
    public Queue.Executable createExecutable() {
        return new NodeExecutable();
    }

    @Override
    public String getName() {
        return nodeName;
    }

    @Override
    public String getDisplayName() {
        return "everyNode on " + nodeName;
    }

    @Override
    public String getFullDisplayName() {
        Run<?, ?> run = run();
        return run == null ? getDisplayName() : run.getFullDisplayName() + " / " + getDisplayName();
    }

    @Override
    public String getUrl() {
        Run<?, ?> run = run();
        return run == null ? "" : run.getUrl();
    }

    @Override
    public boolean isConcurrentBuild() {
        return true;
    }

    @Override
    public boolean isContinued() {
        return started;
    }

    void cancel(Throwable cause) {
        Queue.Item item = Queue.getInstance().getItem(this);
        if (item != null && Queue.getInstance().cancel(item)) {
            context.onFailure(cause);
            return;
        }
        RuntimeState state = RuntimeRegistry.get().get(this);
        if (state != null) {
            state.cancel(cause);
        }
    }

    void resume() {
        if (completed) {
            return;
        }
        Queue queue = Queue.getInstance();
        if (queue.getItem(this) != null || isRunning()) {
            return;
        }
        if (queue.schedule2(this, 0).getItem() == null) {
            context.onFailure(new IOException("Jenkins queue refused node '" + nodeName + "' after restart"));
        }
    }

    private boolean isRunning() {
        Jenkins jenkins = Jenkins.get();
        for (Computer computer : jenkins.getComputers()) {
            for (Executor executor : computer.getAllExecutors()) {
                Queue.Executable executable = executor.getCurrentExecutable();
                if (executable != null && equals(executable.getParent())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Run<?, ?> run() {
        return Run.fromExternalizableId(runId);
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof NodeQueueTask && id.equals(((NodeQueueTask) object).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    private final class NodeExecutable implements ContinuableExecutable {

        @Override
        public SubTask getParent() {
            return NodeQueueTask.this;
        }

        @Override
        public boolean willContinue() {
            return started;
        }

        @Override
        public void run() {
            if (completed) {
                return;
            }
            WorkspaceList.Lease lease = null;
            RuntimeState runtime = null;
            try {
                Executor executor = Executor.currentExecutor();
                if (executor == null) {
                    throw new IllegalStateException("everyNode task has no Jenkins executor");
                }
                Computer computer = executor.getOwner();
                Node node = computer.getNode();
                if (node == null) {
                    throw new IOException("Selected Jenkins node is offline: " + nodeName);
                }
                TaskListener listener = context.get(TaskListener.class);
                Run<?, ?> run = context.get(Run.class);
                if (!(run.getParent() instanceof TopLevelItem)) {
                    throw new IOException(run.getParent() + " is not a top-level Jenkins item");
                }
                FilePath workspace = node.getWorkspaceFor((TopLevelItem) run.getParent());
                if (workspace == null) {
                    throw new IOException("Selected Jenkins node has no workspace: " + nodeName);
                }
                lease = computer.getWorkspaceList().allocate(workspace);
                workspace = lease.path;
                EnvVars environment = computer.getEnvironment();
                environment.overrideExpandingAll(computer.buildEnvironment(listener));
                environment.put("NODE_NAME", node.getSelfLabel().getName());
                environment.put(
                        "NODE_LABELS",
                        node.getAssignedLabels().stream()
                                .map(Label::getName)
                                .sorted()
                                .collect(Collectors.joining(" ")));
                environment.put("EXECUTOR_NUMBER", String.valueOf(executor.getNumber()));
                environment.put("WORKSPACE", workspace.getRemote());
                FilePath temporaryDirectory = WorkspaceList.tempDir(workspace);
                if (temporaryDirectory != null) {
                    environment.put("WORKSPACE_TMP", temporaryDirectory.getRemote());
                }

                runtime = new RuntimeState(lease);
                RuntimeRegistry.get().put(NodeQueueTask.this, runtime);
                if (!started) {
                    started = true;
                    nodeContext = new NodeExecutionContext(
                            NodeQueueTask.this, nodeName, workspace.getRemote(), executor, lease);
                    body = context.newBodyInvoker()
                            .withContexts(environment, nodeContext)
                            .withDisplayName(nodeName)
                            .withCallback(new Callback(NodeQueueTask.this))
                            .start();
                } else if (nodeContext != null) {
                    nodeContext.resume(executor, lease);
                }
                runtime.setBody(body);
                AsynchronousExecution asynchronous = runtime.asynchronous();
                runtime.setExecution(asynchronous);
                throw asynchronous;
            } catch (AsynchronousExecution asynchronous) {
                throw asynchronous;
            } catch (Throwable failure) {
                RuntimeRegistry.get().remove(NodeQueueTask.this);
                if (runtime != null) {
                    runtime.release();
                } else if (lease != null) {
                    lease.release();
                }
                context.onFailure(failure);
            }
        }

        @Override
        public String toString() {
            return getFullDisplayName();
        }
    }

    /** Prevents an ordinary label atom collision from moving an exact-node branch to another node. */
    @Extension
    public static final class ExactNodeDispatcher extends QueueTaskDispatcher {

        @Override
        public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            if (item.task instanceof NodeQueueTask task
                    && !task.selfLabel.equals(node.getSelfLabel().getName())) {
                return new CauseOfBlockage() {
                    @Override
                    public String getShortDescription() {
                        return "Must run on " + task.nodeName;
                    }
                };
            }
            return null;
        }
    }

    private static final class Callback extends BodyExecutionCallback {

        private static final long serialVersionUID = 1L;

        private final NodeQueueTask task;

        private Callback(NodeQueueTask task) {
            this.task = task;
        }

        @Override
        public void onSuccess(StepContext bodyContext, Object result) {
            task.finish(null);
        }

        @Override
        public void onFailure(StepContext bodyContext, Throwable failure) {
            task.finish(failure);
        }
    }

    private void finish(Throwable failure) {
        completed = true;
        RuntimeState state = RuntimeRegistry.get().remove(this);
        if (state != null) {
            state.complete();
        }
        if (failure == null) {
            context.onSuccess(null);
        } else {
            context.onFailure(failure);
        }
    }

    @Extension
    public static final class RuntimeRegistry {

        private final Map<NodeQueueTask, RuntimeState> states = new ConcurrentHashMap<>();

        static RuntimeRegistry get() {
            return Jenkins.get().getExtensionList(RuntimeRegistry.class).get(0);
        }

        RuntimeState get(NodeQueueTask task) {
            return states.get(task);
        }

        void put(NodeQueueTask task, RuntimeState state) {
            states.put(task, state);
        }

        RuntimeState remove(NodeQueueTask task) {
            return states.remove(task);
        }
    }

    private static final class RuntimeState {

        private final WorkspaceList.Lease lease;
        private BodyExecution body;
        private AsynchronousExecution execution;
        private boolean finished;

        private RuntimeState(WorkspaceList.Lease lease) {
            this.lease = lease;
        }

        synchronized void setBody(BodyExecution body) {
            this.body = body;
        }

        synchronized void setExecution(AsynchronousExecution execution) {
            this.execution = execution;
            if (finished) {
                execution.completed(null);
            }
        }

        AsynchronousExecution asynchronous() {
            return new AsynchronousExecution() {
                @Override
                public void interrupt(boolean forShutdown) {
                    if (!forShutdown) {
                        cancel(new InterruptedException("everyNode executor interrupted"));
                    }
                }

                @Override
                public boolean blocksRestart() {
                    return false;
                }

                @Override
                public boolean displayCell() {
                    return true;
                }
            };
        }

        synchronized void cancel(Throwable cause) {
            if (body != null) {
                body.cancel(cause);
            }
        }

        synchronized void complete() {
            if (finished) {
                return;
            }
            finished = true;
            release();
            if (execution != null) {
                execution.completed(null);
            }
        }

        synchronized void release() {
            lease.release();
        }
    }
}
