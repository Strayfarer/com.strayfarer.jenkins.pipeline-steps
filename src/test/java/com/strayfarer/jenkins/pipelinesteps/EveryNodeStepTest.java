package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Functions;
import hudson.model.Action;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.DumbSlave;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class EveryNodeStepTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void snapshotsMatchingOnlineNodesAndRunsNextAvailableSequentially() throws Throwable {
        sessions.then(j -> {
            DumbSlave second = j.createOnlineSlave();
            second.setLabelString("unity linux");
            DumbSlave first = j.createOnlineSlave();
            first.setLabelString("unity linux");
            DumbSlave excluded = j.createOnlineSlave(Label.get("unity"));
            DumbSlave offline = j.createOnlineSlave(Label.get("unity linux"));
            requireNonNull(offline.getComputer()).disconnect(null).get(30, TimeUnit.SECONDS);
            List<DumbSlave> selected = Stream.of(first, second)
                    .sorted(java.util.Comparator.comparing(DumbSlave::getNodeName))
                    .toList();
            WorkflowRun blocker = startBlocker(j, selected.get(0), "sequential-first-blocker");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "next-available");
            job.setDefinition(new CpsFlowDefinition("""
                    everyNode('unity && linux') {
                        echo "visited=${env.NODE_NAME}"
                        echo "stage=${env.STAGE_NAME}"
                    }
                    """, true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();

            try {
                j.waitForMessage("visited=" + selected.get(1).getNodeName(), run);
                assertTrue(run.isBuilding(), JenkinsRule.getLog(run));
                j.assertLogNotContains("visited=" + selected.get(0).getNodeName(), run);
            } finally {
                blocker.doStop();
                j.waitForCompletion(blocker);
            }

            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            String log = JenkinsRule.getLog(run);
            assertEquals(1, occurrences(log, "visited=" + selected.get(0).getNodeName()));
            assertEquals(1, occurrences(log, "visited=" + selected.get(1).getNodeName()));
            assertEquals(0, occurrences(log, "visited=" + excluded.getNodeName()));
            assertEquals(0, occurrences(log, "visited=" + offline.getNodeName()));
            assertEquals(1, occurrences(log, "stage=" + selected.get(0).getNodeName()));
            assertEquals(1, occurrences(log, "stage=" + selected.get(1).getNodeName()));
            assertTrue(
                    log.indexOf("visited=" + selected.get(1).getNodeName())
                            < log.indexOf("visited=" + selected.get(0).getNodeName()),
                    log);
            assertEquals(selected.stream().map(DumbSlave::getNodeName).sorted().toList(), stageNames(run));
        });
    }

    @Test
    void omittedLabelRunsOnEveryOnlineNode() throws Throwable {
        sessions.then(j -> {
            j.jenkins.setNumExecutors(1);
            DumbSlave first = j.createOnlineSlave(Label.get("first-label"));
            DumbSlave second = j.createOnlineSlave(Label.get("second-label"));
            DumbSlave offline = j.createOnlineSlave(Label.get("offline-label"));
            requireNonNull(offline.getComputer()).disconnect(null).get(30, TimeUnit.SECONDS);

            WorkflowRun run = build(j, """
                    everyNode {
                        echo "all-visited=${env.NODE_NAME}"
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            String log = JenkinsRule.getLog(run);
            assertEquals(
                    1,
                    occurrences(log, "all-visited=" + j.jenkins.getSelfLabel().getName()));
            assertEquals(1, occurrences(log, "all-visited=" + first.getNodeName()));
            assertEquals(1, occurrences(log, "all-visited=" + second.getNodeName()));
            assertEquals(0, occurrences(log, "all-visited=" + offline.getNodeName()));
        });
    }

    @Test
    void matchingCurrentNodeRunsFirstWithoutAllocatingAnotherExecutor() throws Throwable {
        sessions.then(j -> {
            DumbSlave current = j.createOnlineSlave(Label.get("self-target"));
            current.setNumExecutors(1);
            DumbSlave other = j.createOnlineSlave(Label.get("self-target"));
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "current-node-first");
            job.setDefinition(new CpsFlowDefinition(("""
                    node('%s') {
                        everyNode('self-target') {
                            echo "self-visited=${env.NODE_NAME}"
                        }
                    }
                    """).formatted(current.getNodeName()), true));

            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).get(30, TimeUnit.SECONDS);

            j.assertBuildStatusSuccess(run);
            String currentVisit = "self-visited=" + current.getNodeName();
            String otherVisit = "self-visited=" + other.getNodeName();
            String log = JenkinsRule.getLog(run);
            assertEquals(1, occurrences(log, currentVisit));
            assertEquals(1, occurrences(log, otherVisit));
            assertTrue(log.indexOf(currentVisit) < log.indexOf(otherVisit), log);
            assertEquals(
                    Stream.of(current.getNodeName(), other.getNodeName())
                            .sorted()
                            .toList(),
                    stageNames(run));
        });
    }

    @Test
    void parallelModeNamesAndRunsEveryConcreteBranch() throws Throwable {
        sessions.then(j -> {
            DumbSlave first = j.createOnlineSlave(Label.get("parallel-nodes"));
            DumbSlave second = j.createOnlineSlave(Label.get("parallel-nodes"));

            WorkflowRun run = build(j, """
                    everyNode(label: 'parallel-nodes', parallel: true) {
                        echo "parallel-visited=${env.NODE_NAME}"
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            String log = JenkinsRule.getLog(run);
            assertEquals(1, occurrences(log, "parallel-visited=" + first.getNodeName()));
            assertEquals(1, occurrences(log, "parallel-visited=" + second.getNodeName()));
            assertTrue(log.contains("(" + first.getNodeName() + ")"), log);
            assertTrue(log.contains("(" + second.getNodeName() + ")"), log);
            assertEquals(
                    Stream.of(first.getNodeName(), second.getNodeName())
                            .sorted()
                            .toList(),
                    stageNames(run));
        });
    }

    @Test
    void positionalArgumentsSelectParallelMode() throws Throwable {
        sessions.then(j -> {
            DumbSlave first = j.createOnlineSlave(Label.get("positional-nodes"));
            DumbSlave second = j.createOnlineSlave(Label.get("positional-nodes"));

            WorkflowRun run = build(j, """
                    everyNode('positional-nodes', true) {
                        echo "positional-visited=${env.NODE_NAME}"
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            String log = JenkinsRule.getLog(run);
            assertEquals(1, occurrences(log, "positional-visited=" + first.getNodeName()));
            assertEquals(1, occurrences(log, "positional-visited=" + second.getNodeName()));
        });
    }

    @Test
    void noOnlineMatchesFailsClearly() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    everyNode('does-not-exist') {
                        echo 'must-not-run'
                    }
                    """);

            j.assertBuildStatus(Result.FAILURE, run);
            j.assertLogContains("No online Jenkins nodes match label 'does-not-exist'", run);
            j.assertLogNotContains("must-not-run", run);
        });
    }

    @Test
    void branchFailureFailsTheStep() throws Throwable {
        sessions.then(j -> {
            j.createOnlineSlave(Label.get("failure-nodes"));
            j.createOnlineSlave(Label.get("failure-nodes"));

            WorkflowRun run = build(j, """
                    everyNode(label: 'failure-nodes', parallel: true) {
                        error "branch-failed-${env.NODE_NAME}"
                    }
                    """);

            j.assertBuildStatus(Result.FAILURE, run);
            j.assertLogContains("branch-failed-", run);
        });
    }

    @Test
    void commandStepsReceiveTheAllocatedNodeContext() throws Throwable {
        sessions.then(j -> {
            DumbSlave node = j.createOnlineSlave(Label.get("command-node"));
            String command = Functions.isWindows()
                    ? "Write-Output \"command-node=$env:NODE_NAME\""
                    : "echo command-node=$NODE_NAME";

            WorkflowRun run = build(j, """
                    everyNode('command-node') {
                        exec '%s'
                    }
                    """.formatted(command));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("command-node=" + node.getNodeName(), run);
        });
    }

    @Test
    void bodyReceivesNoPositionalArguments() throws Throwable {
        sessions.then(j -> {
            j.createOnlineSlave(Label.get("argument-node"));

            WorkflowRun run = build(j, """
                    everyNode('argument-node') {
                        echo "argument-is-null=${it == null}"
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("argument-is-null=true", run);
        });
    }

    @Test
    void exactNodeBranchWaitsForItsSnapshottedNodeToReconnect() throws Throwable {
        sessions.then(j -> {
            DumbSlave first = j.createOnlineSlave(Label.get("exact-target"));
            DumbSlave selected = j.createOnlineSlave(Label.get("exact-target"));
            DumbSlave collision = j.createOnlineSlave(Label.get(selected.getNodeName()));
            WorkflowRun blocker = startBlocker(j, selected, "exact-selected-blocker");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "exact-node");
            job.setDefinition(new CpsFlowDefinition(("""
                    everyNode('exact-target') {
                        echo "exact-visited=${env.NODE_NAME}"
                        if (env.NODE_NAME == '%s') {
                            sleep 5
                        }
                    }
                    """).formatted(first.getNodeName()), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("exact-visited=" + first.getNodeName(), run);

            blocker.doStop();
            j.waitForCompletion(blocker);
            Computer selectedComputer = requireNonNull(selected.getComputer());
            selectedComputer.disconnect(null).get(30, TimeUnit.SECONDS);
            Thread.sleep(6_000);
            assertTrue(run.isBuilding(), JenkinsRule.getLog(run));
            j.assertLogNotContains("exact-visited=" + collision.getNodeName(), run);

            selectedComputer.connect(true).get(30, TimeUnit.SECONDS);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            j.assertLogContains("exact-visited=" + selected.getNodeName(), run);
        });
    }

    @Test
    void parallelDurableBranchesAbortCleanly() throws Throwable {
        sessions.then(j -> {
            List<DumbSlave> nodes = List.of(
                    j.createOnlineSlave(Label.get("abort-node")),
                    j.createOnlineSlave(Label.get("abort-node")),
                    j.createOnlineSlave(Label.get("abort-node")));
            String firstCommand = Functions.isWindows() ? "Write-Output 'every-node-first'" : "echo every-node-first";
            String runningCommand = Functions.isWindows()
                    ? "Write-Output 'every-node-running'; Start-Sleep -Seconds 60"
                    : "echo every-node-running; sleep 60";
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "abort-every-node");
            job.setDefinition(new CpsFlowDefinition("""
                    everyNode(label: 'abort-node', parallel: true) {
                        exec "%s"
                        exec "%s"
                    }
                    """.formatted(firstCommand, runningCommand), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            try {
                assertTrue(waitForOccurrences(run, "every-node-running", nodes.size()), JenkinsRule.getLog(run));

                run.doStop();
                assertTrue(waitForCompletion(run, 45), JenkinsRule.getLog(run));

                j.assertBuildStatus(Result.ABORTED, run);
                String log = JenkinsRule.getLog(run);
                assertEquals(nodes.size(), occurrences(log, "every-node-first"));
                assertEquals(nodes.size(), occurrences(log, "every-node-running"));
                assertTrue(!log.contains("cannot start writing logs to a finished node"), log);
            } finally {
                if (run.isBuilding()) {
                    run.doKill();
                    j.waitForCompletion(run);
                }
            }
        });
    }

    @Test
    void runningBranchSurvivesControllerRestart() throws Throwable {
        sessions.then(j -> {
            j.jenkins.setNumExecutors(1);
            j.jenkins.setLabelString("restart-node");
            String command = Functions.isWindows()
                    ? "Write-Output 'before-node-restart'; Start-Sleep -Seconds 8; Write-Output 'after-node-restart'"
                    : "echo before-node-restart; sleep 8; echo after-node-restart";
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "restart-every-node");
            job.setDefinition(new CpsFlowDefinition("""
                    everyNode('restart-node') {
                        exec "%s"
                    }
                    """.formatted(command), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("before-node-restart", run);
        });
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.getItemByFullName("restart-every-node", WorkflowJob.class);
            assertNotNull(job);
            WorkflowRun lastBuild = job.getLastBuild();
            assertNotNull(lastBuild);
            WorkflowRun run = j.waitForCompletion(lastBuild);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("before-node-restart", run);
            j.assertLogContains("after-node-restart", run);
        });
    }

    @Test
    void queueTaskPreservesBuildAuthenticationAndOwner() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, "echo 'authentication-source'");
            j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
            User user = requireNonNull(User.getById("every-node-user", true));
            NodeQueueTask task;
            try (ACLContext ignored = ACL.as2(user.impersonate2())) {
                task = new NodeQueueTask(new RunStepContext(run), "selected-node", "selected-node");
            }

            assertEquals(run.getParent(), task.getOwnerTask());
            var authentication = requireNonNull(new NodeQueueTask.AuthenticationFromBuild()
                    .getAuthenticators()
                    .getFirst()
                    .authenticate2(task));
            assertEquals(user.getId(), authentication.getName());
        });
    }

    @Test
    void cancellationBeforeRuntimeRegistrationCompletesTheTask() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, "echo 'cancellation-source'");
            RunStepContext context = new RunStepContext(run);
            NodeQueueTask task = new NodeQueueTask(context, "selected-node", "selected-node");
            InterruptedException cancellation = new InterruptedException("cancelled during handoff");

            task.cancel(cancellation);

            assertSame(cancellation, context.failure);
        });
    }

    @Test
    void externallyCancelledQueueItemCompletesTheStep() throws Throwable {
        sessions.then(j -> {
            DumbSlave node = j.createOnlineSlave(Label.get("cancel-queued-node"));
            WorkflowRun blocker = startBlocker(j, node, "cancel-blocker");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "cancel-queued-every-node");
            job.setDefinition(new CpsFlowDefinition("""
                    everyNode('cancel-queued-node') {
                        echo 'cancelled-body-must-not-run'
                    }
                    """, true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            boolean completed;
            try {
                Queue.Item item = waitForNodeQueueTask();
                ((NodeQueueTask) item.task).reportWaiting();
                j.assertLogContains("Still waiting to schedule everyNode on '" + node.getNodeName() + "'", run);
                j.assertLogContains("Waiting for next available executor on", run);
                assertTrue(Queue.getInstance().cancel(item));
                completed = waitForCompletion(run);
            } finally {
                if (run.isBuilding()) {
                    run.doStop();
                }
                blocker.doStop();
                j.waitForCompletion(blocker);
            }

            assertTrue(completed, JenkinsRule.getLog(run));
            j.assertBuildStatus(Result.ABORTED, run);
            j.assertLogNotContains("cancelled-body-must-not-run", run);
        });
    }

    @Test
    void parallelQueueRefusalCancelsPreviouslyScheduledBranches() throws Throwable {
        sessions.then(j -> {
            DumbSlave first = j.createOnlineSlave(Label.get("rollback-nodes"));
            DumbSlave second = j.createOnlineSlave(Label.get("rollback-nodes"));
            List<DumbSlave> nodes = Stream.of(first, second)
                    .sorted(java.util.Comparator.comparing(DumbSlave::getNodeName))
                    .toList();
            WorkflowRun firstBlocker = startBlocker(j, nodes.get(0), "rollback-blocker-first");
            WorkflowRun secondBlocker = startBlocker(j, nodes.get(1), "rollback-blocker-second");
            RefuseNode.nodeName = nodes.get(1).getNodeName();
            try {
                WorkflowRun run = build(j, """
                        everyNode(label: 'rollback-nodes', parallel: true) {
                            echo "orphan-body=${env.NODE_NAME}"
                        }
                        """);

                j.assertBuildStatus(Result.FAILURE, run);
                j.assertLogContains("Jenkins queue refused nodes [" + RefuseNode.nodeName + "]", run);
                assertTrue(
                        waitForNoNodeQueueTask(run.getParent()),
                        () -> "Queued tasks remain for " + run.getParent().getFullName() + ": "
                                + queuedNodeTasks(run.getParent()));
                j.assertLogNotContains("orphan-body=", run);
            } finally {
                for (Queue.Item item : Queue.getInstance().getItems()) {
                    if (item.task instanceof NodeQueueTask) {
                        Queue.getInstance().cancel(item);
                    }
                }
                firstBlocker.doStop();
                secondBlocker.doStop();
                j.waitForCompletion(firstBlocker);
                j.waitForCompletion(secondBlocker);
                RefuseNode.nodeName = null;
            }
        });
    }

    @TestExtension("parallelQueueRefusalCancelsPreviouslyScheduledBranches")
    public static final class RefuseNode extends Queue.QueueDecisionHandler {

        private static volatile String nodeName;

        @Override
        public boolean shouldSchedule(Queue.Task task, List<Action> actions) {
            return !(task instanceof NodeQueueTask && task.getName().equals(nodeName));
        }
    }

    private static WorkflowRun build(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return requireNonNull(job.scheduleBuild2(0)).get();
    }

    private static WorkflowRun startBlocker(JenkinsRule j, DumbSlave node, String name) throws Exception {
        WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(("""
                node('%s') {
                    echo 'occupied-%s'
                    sleep 60
                }
                """).formatted(node.getNodeName(), name), true));
        WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
        j.waitForMessage("occupied-" + name, run);
        return run;
    }

    @SuppressWarnings("BusyWait") // Poll the Jenkins queue with a bounded timeout.
    private static Queue.Item waitForNodeQueueTask() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (Queue.Item item : Queue.getInstance().getItems()) {
                if (item.task instanceof NodeQueueTask) {
                    return item;
                }
            }
            Thread.sleep(100);
        }
        return fail("NodeQueueTask was not queued within 15 seconds");
    }

    @SuppressWarnings("BusyWait") // Poll Pipeline completion with a bounded timeout.
    private static boolean waitForCompletion(WorkflowRun run) throws InterruptedException {
        return waitForCompletion(run, 15);
    }

    @SuppressWarnings("BusyWait") // Poll Pipeline completion with a bounded timeout.
    private static boolean waitForCompletion(WorkflowRun run, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (run.isBuilding() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        return !run.isBuilding();
    }

    @SuppressWarnings("BusyWait") // Poll streamed Pipeline output with a bounded timeout.
    private static boolean waitForOccurrences(WorkflowRun run, String message, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (occurrences(JenkinsRule.getLog(run), message) == expected) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static List<String> queuedNodeTasks(WorkflowJob owner) {
        List<String> tasks = new ArrayList<>();
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.task instanceof NodeQueueTask && item.task.getOwnerTask().equals(owner)) {
                tasks.add(item.task.getName() + " (queue id " + item.getId() + ")");
            }
        }
        return tasks;
    }

    @SuppressWarnings("BusyWait") // Poll the Jenkins queue with a bounded timeout.
    private static boolean waitForNoNodeQueueTask(WorkflowJob owner) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!queuedNodeTasks(owner).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        return queuedNodeTasks(owner).isEmpty();
    }

    private static final class RunStepContext extends StepContext {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Run<?, ?> run;
        private Throwable failure;

        private RunStepContext(Run<?, ?> run) {
            this.run = run;
        }

        @Override
        public <T> T get(Class<T> key) {
            return key == Run.class ? key.cast(run) : null;
        }

        @Override
        public void onSuccess(Object result) {}

        @Override
        public void onFailure(@NonNull Throwable failure) {
            this.failure = failure;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public ListenableFuture<Void> saveState() {
            return Futures.immediateVoidFuture();
        }

        @Override
        public void setResult(Result result) {}

        @Override
        public BodyInvoker newBodyInvoker() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasBody() {
            return false;
        }

        @Override
        public boolean equals(Object object) {
            return object == this;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static List<String> stageNames(WorkflowRun run) throws java.io.IOException {
        List<String> names = new ArrayList<>();
        for (FlowNode node : new FlowGraphWalker(requireNonNull(run.getExecution()))) {
            if (node instanceof StepStartNode start
                    && start.isBody()
                    && start.getDescriptor().getFunctionName().equals("stage")) {
                names.add(start.getDisplayName());
            }
        }
        return names.stream().sorted().toList();
    }
}
