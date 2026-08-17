package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;

final class NodeExecutionContext implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final NodeQueueTask task;
    private final String nodeName;
    private final String path;
    private transient Executor executor;
    private transient WorkspaceList.Lease lease;

    NodeExecutionContext(
            NodeQueueTask task, String nodeName, String path, Executor executor, WorkspaceList.Lease lease) {
        this.task = task;
        this.nodeName = nodeName;
        this.path = path;
        this.executor = executor;
        this.lease = lease;
    }

    void resume(Executor executor, WorkspaceList.Lease lease) {
        this.executor = executor;
        this.lease = lease;
    }

    private Node node() {
        Jenkins jenkins = Jenkins.get();
        return nodeName.equals(jenkins.getSelfLabel().getName()) ? jenkins : jenkins.getNode(nodeName);
    }

    private Computer computer() {
        Node node = node();
        return node == null ? null : node.toComputer();
    }

    private Executor executor() {
        if (executor != null) {
            return executor;
        }
        Computer computer = computer();
        if (computer != null) {
            for (Executor candidate : computer.getExecutors()) {
                Queue.Executable executable = candidate.getCurrentExecutable();
                if (executable != null && task.equals(executable.getParent())) {
                    executor = candidate;
                    break;
                }
            }
        }
        return executor;
    }

    private FilePath filePath() throws IOException {
        Computer computer = computer();
        VirtualChannel channel = computer == null ? null : computer.getChannel();
        if (channel == null) {
            throw new IOException("Selected Jenkins node is offline: " + nodeName);
        }
        return new FilePath(channel, path);
    }

    private abstract static class Translator<T> extends DynamicContext.Typed<T> {

        @Override
        protected T get(DelegatedContext context) throws IOException, InterruptedException {
            NodeExecutionContext nodeContext = context.get(NodeExecutionContext.class);
            return nodeContext == null ? null : value(nodeContext, context);
        }

        abstract T value(NodeExecutionContext nodeContext, DelegatedContext context)
                throws IOException, InterruptedException;
    }

    @Extension
    public static final class FilePathTranslator extends Translator<FilePath> {

        @Override
        protected @NonNull Class<FilePath> type() {
            return FilePath.class;
        }

        @Override
        FilePath value(NodeExecutionContext nodeContext, DelegatedContext context) throws IOException {
            return nodeContext.filePath();
        }
    }

    @Extension
    public static final class LauncherTranslator extends Translator<Launcher> {

        @Override
        protected @NonNull Class<Launcher> type() {
            return Launcher.class;
        }

        @Override
        Launcher value(NodeExecutionContext nodeContext, DelegatedContext context)
                throws IOException, InterruptedException {
            Node node = nodeContext.node();
            if (node == null) {
                throw new IOException("Selected Jenkins node no longer exists: " + nodeContext.nodeName);
            }
            return node.createLauncher(context.get(TaskListener.class));
        }
    }

    @Extension
    public static final class ComputerTranslator extends Translator<Computer> {

        @Override
        protected @NonNull Class<Computer> type() {
            return Computer.class;
        }

        @Override
        Computer value(NodeExecutionContext nodeContext, DelegatedContext context) {
            return nodeContext.computer();
        }
    }

    @Extension
    public static final class NodeTranslator extends Translator<Node> {

        @Override
        protected @NonNull Class<Node> type() {
            return Node.class;
        }

        @Override
        Node value(NodeExecutionContext nodeContext, DelegatedContext context) {
            return nodeContext.node();
        }
    }

    @Extension
    public static final class ExecutorTranslator extends Translator<Executor> {

        @Override
        protected @NonNull Class<Executor> type() {
            return Executor.class;
        }

        @Override
        Executor value(NodeExecutionContext nodeContext, DelegatedContext context) {
            return nodeContext.executor();
        }
    }

    @Extension
    public static final class LeaseTranslator extends Translator<WorkspaceList.Lease> {

        @Override
        protected @NonNull Class<WorkspaceList.Lease> type() {
            return WorkspaceList.Lease.class;
        }

        @Override
        WorkspaceList.Lease value(NodeExecutionContext nodeContext, DelegatedContext context) {
            return nodeContext.lease;
        }
    }
}
