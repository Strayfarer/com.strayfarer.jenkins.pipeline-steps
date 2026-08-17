package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.model.User;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import hudson.model.queue.QueueTaskDispatcher;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.SubTask;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessControlled;
import hudson.slaves.WorkspaceList;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorProvider;
import org.jenkinsci.plugins.durabletask.executors.ContinuableExecutable;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

final class NodeQueueTask implements ContinuedTask, Serializable, AccessControlled {

    private static final long serialVersionUID = 1L;

    private final String id = UUID.randomUUID().toString();
    private final StepContext context;
    private final String nodeName;
    private final String selfLabel;
    private final String runId;
    private final String authentication;
    private volatile boolean started;
    private volatile boolean completed;
    private volatile BodyExecution body;
    private transient QueueTaskFuture<?> queueFuture;
    private NodeExecutionContext nodeContext;
    private Throwable cancellation;

    NodeQueueTask(StepContext context, String nodeName, String selfLabel) throws IOException, InterruptedException {
        this.context = context;
        this.nodeName = nodeName;
        this.selfLabel = selfLabel;
        this.runId = context.get(Run.class).getExternalizableId();
        Authentication current = Jenkins.getAuthentication2();
        this.authentication = current.equals(ACL.SYSTEM2) ? null : current.getName();
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

    @NonNull
    @Override
    public Queue.Task getOwnerTask() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                Job<?, ?> job = jenkins.getItemByFullName(jobFullName(), Job.class);
                if (job instanceof Queue.Task task) {
                    return task;
                }
            }
        }
        Run<?, ?> run = run();
        return run != null && run.getParent() instanceof Queue.Task task ? task : this;
    }

    @NonNull
    @Override
    public ACL getACL() {
        try {
            Run<?, ?> run = run();
            if (run != null) {
                return run.getACL();
            }
            Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName(), Job.class);
            if (job != null) {
                return job.getACL();
            }
        } catch (AccessDeniedException ignored) {
            // Fall back to the Jenkins root ACL when the current authentication cannot read the job.
        } catch (RuntimeException ignored) {
            // Keep queue cleanup possible if the owning item can no longer be resolved.
        }
        return Jenkins.get().getACL();
    }

    @Override
    public void checkAbortPermission() {
        checkPermission(Item.CANCEL);
    }

    @Override
    public boolean hasAbortPermission() {
        return hasPermission(Item.CANCEL);
    }

    @Override
    public boolean isContinued() {
        return started;
    }

    synchronized Queue.WaitingItem schedule() {
        Queue.WaitingItem item = Queue.getInstance().schedule2(this, 0).getCreateItem();
        if (item != null) {
            queueFuture = item.getFuture();
        }
        return item;
    }

    void cancel(Throwable cause) {
        QueueTaskFuture<?> future;
        synchronized (this) {
            if (completed) {
                return;
            }
            if (cancellation == null) {
                cancellation = cause;
            }
            future = queueFuture;
        }
        Queue queue = Queue.getInstance();
        Queue.Item item = queue.getItem(this);
        if (item != null && queue.cancel(item)) {
            return;
        }
        if (future != null && future.cancel(true)) {
            return;
        }
        RuntimeState state = RuntimeRegistry.get().get(this);
        if (state != null) {
            state.cancel(cause);
        } else if (!isRunning()) {
            finish(cause);
        }
    }

    private void queueCancelled() {
        Throwable cause;
        synchronized (this) {
            cause = cancellation;
        }
        finish(cause == null ? new FlowInterruptedException(Result.ABORTED, true, new QueueTaskCancelled()) : cause);
    }

    void resume() {
        if (completed) {
            return;
        }
        Queue queue = Queue.getInstance();
        if (queue.getItem(this) != null || isRunning()) {
            return;
        }
        if (schedule() == null) {
            finish(new IOException("Jenkins queue refused node '" + nodeName + "' after restart"));
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

    private String jobFullName() {
        return runId.substring(0, runId.lastIndexOf('#'));
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
                Throwable cancelled;
                synchronized (NodeQueueTask.this) {
                    cancelled = cancellation;
                    if (!completed && cancelled == null) {
                        RuntimeRegistry.get().put(NodeQueueTask.this, runtime);
                    }
                }
                if (completed || cancelled != null) {
                    runtime.release();
                    if (cancelled != null) {
                        finish(cancelled);
                    }
                    return;
                }
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
                if (runtime != null) {
                    runtime.release();
                } else if (lease != null) {
                    lease.release();
                }
                finish(failure);
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

    @Extension(ordinal = 959)
    public static final class AuthenticationFromBuild extends QueueItemAuthenticatorProvider {

        @NonNull
        @Override
        public List<QueueItemAuthenticator> getAuthenticators() {
            return List.of(new QueueItemAuthenticator() {
                @Override
                public Authentication authenticate2(Queue.Task task) {
                    if (task instanceof NodeQueueTask nodeTask) {
                        if (Jenkins.ANONYMOUS2.getName().equals(nodeTask.authentication)) {
                            return Jenkins.ANONYMOUS2;
                        }
                        if (nodeTask.authentication != null) {
                            User user = User.getById(nodeTask.authentication, false);
                            return user == null ? Jenkins.ANONYMOUS2 : user.impersonate2();
                        }
                    }
                    return null;
                }
            });
        }
    }

    @Extension
    public static final class CancelledItemListener extends QueueListener {

        @Override
        public void onLeft(Queue.LeftItem item) {
            if (item.isCancelled() && item.task instanceof NodeQueueTask task) {
                task.queueCancelled();
            }
        }
    }

    private static final class QueueTaskCancelled extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        @Override
        public String getShortDescription() {
            return "everyNode queue item was cancelled";
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
        synchronized (this) {
            if (completed) {
                return;
            }
            completed = true;
        }
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
        private boolean released;
        private Throwable cancellation;

        private RuntimeState(WorkspaceList.Lease lease) {
            this.lease = lease;
        }

        void setBody(BodyExecution body) {
            Throwable cancelled;
            synchronized (this) {
                this.body = body;
                cancelled = cancellation;
            }
            if (cancelled != null) {
                body.cancel(cancelled);
            }
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

        void cancel(Throwable cause) {
            BodyExecution current;
            synchronized (this) {
                if (cancellation == null) {
                    cancellation = cause;
                }
                current = body;
            }
            if (current != null) {
                current.cancel(cause);
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
            if (!released) {
                released = true;
                lease.release();
            }
        }
    }
}
