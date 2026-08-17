package com.strayfarer.jenkins.pipelinesteps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Functions;
import hudson.model.Label;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class EveryNodeStepTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void snapshotsOnlyMatchingOnlineNodesAndRunsSequentially() throws Throwable {
        sessions.then(j -> {
            DumbSlave second = j.createOnlineSlave();
            second.setLabelString("unity linux");
            DumbSlave first = j.createOnlineSlave();
            first.setLabelString("unity linux");
            DumbSlave excluded = j.createOnlineSlave(Label.get("unity"));
            DumbSlave offline = j.createOnlineSlave(Label.get("unity linux"));
            offline.getComputer().disconnect(null).get(30, TimeUnit.SECONDS);

            WorkflowRun run = build(j, """
                    everyNode('unity && linux') {
                        echo "visited=${env.NODE_NAME}"
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            String log = JenkinsRule.getLog(run);
            List<String> expected = List.of(first.getNodeName(), second.getNodeName()).stream()
                    .sorted()
                    .toList();
            assertEquals(1, occurrences(log, "visited=" + expected.get(0)));
            assertEquals(1, occurrences(log, "visited=" + expected.get(1)));
            assertEquals(0, occurrences(log, "visited=" + excluded.getNodeName()));
            assertEquals(0, occurrences(log, "visited=" + offline.getNodeName()));
            assertTrue(log.indexOf("visited=" + expected.get(0)) < log.indexOf("visited=" + expected.get(1)), log);
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
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "exact-node");
            job.setDefinition(new CpsFlowDefinition(("""
                    everyNode('exact-target') {
                        echo "exact-visited=${env.NODE_NAME}"
                        if (env.NODE_NAME == '%s') {
                            sleep 5
                        }
                    }
                    """).formatted(first.getNodeName()), true));
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.waitForMessage("exact-visited=" + first.getNodeName(), run);

            selected.getComputer().disconnect(null).get(30, TimeUnit.SECONDS);
            Thread.sleep(6_000);
            assertTrue(run.isBuilding(), JenkinsRule.getLog(run));
            j.assertLogNotContains("exact-visited=" + collision.getNodeName(), run);

            selected.getComputer().connect(true).get(30, TimeUnit.SECONDS);
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            j.assertLogContains("exact-visited=" + selected.getNodeName(), run);
        });
    }

    @Test
    void abortPreservesInterruptionResult() throws Throwable {
        sessions.then(j -> {
            j.createOnlineSlave(Label.get("abort-node"));
            String command = Functions.isWindows()
                    ? "Write-Output 'every-node-started'; Start-Sleep -Seconds 60"
                    : "echo every-node-started; sleep 60";
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "abort-every-node");
            job.setDefinition(new CpsFlowDefinition("""
                    everyNode('abort-node') {
                        exec "%s"
                    }
                    """.formatted(command), true));
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.waitForMessage("every-node-started", run);

            run.doStop();
            j.waitForCompletion(run);

            j.assertBuildStatus(Result.ABORTED, run);
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
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.waitForMessage("before-node-restart", run);
        });
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.getItemByFullName("restart-every-node", WorkflowJob.class);
            WorkflowRun run = j.waitForCompletion(job.getLastBuild());

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("before-node-restart", run);
            j.assertLogContains("after-node-restart", run);
        });
    }

    private static WorkflowRun build(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job.scheduleBuild2(0).get();
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
}
