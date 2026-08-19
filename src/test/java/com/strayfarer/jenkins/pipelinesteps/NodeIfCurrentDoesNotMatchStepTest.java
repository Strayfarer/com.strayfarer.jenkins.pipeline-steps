package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Label;
import hudson.model.Result;
import hudson.slaves.DumbSlave;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class NodeIfCurrentDoesNotMatchStepTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void exactNodeNameReusesTheCurrentExecutorAndWorkspace() throws Throwable {
        sessions.then(j -> {
            DumbSlave node = j.createOnlineSlave(Label.get("unrelated-label"));

            WorkflowRun run = build(j, ("""
                    node('%s') {
                        def outerWorkspace = pwd()
                        def result = nodeIfCurrentDoesNotMatch('%s') {
                            if (env.NODE_NAME != '%s' || pwd() != outerWorkspace) {
                                error 'exact node context was not reused'
                            }
                            return 'exact-result'
                        }
                        echo "returned=${result}"
                    }
                    """).formatted(node.getNodeName(), node.getNodeName(), node.getNodeName()));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains(
                    "Reusing current Jenkins node '" + node.getNodeName() + "' for label '" + node.getNodeName() + "'",
                    run);
            j.assertLogContains("returned=exact-result", run);
            assertSingleNodeAllocation(run);
        });
    }

    @Test
    void matchingLabelExpressionReusesTheCurrentExecutor() throws Throwable {
        sessions.then(j -> {
            DumbSlave node = j.createOnlineSlave();
            node.setLabelString("linux gpu");

            WorkflowRun run = build(j, ("""
                    node('%s') {
                        nodeIfCurrentDoesNotMatch('linux && gpu') {
                            echo "expression-node=${env.NODE_NAME}"
                        }
                    }
                    """).formatted(node.getNodeName()));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("expression-node=" + node.getNodeName(), run);
            j.assertLogContains("Reusing current Jenkins node", run);
            assertSingleNodeAllocation(run);
        });
    }

    @Test
    void nonmatchingAndMissingContextsUseNativeNodeAllocationAndReturnResults() throws Throwable {
        sessions.then(j -> {
            DumbSlave first = j.createOnlineSlave(Label.get("first-node"));
            DumbSlave second = j.createOnlineSlave(Label.get("second-node"));

            WorkflowRun switched = build(j, ("""
                    node('%s') {
                        def result = nodeIfCurrentDoesNotMatch('second-node') {
                            echo "switched-node=${env.NODE_NAME}"
                            return 'switched-result'
                        }
                        echo "returned=${result}"
                    }
                    """).formatted(first.getNodeName()));
            j.assertBuildStatusSuccess(switched);
            j.assertLogContains("switched-node=" + second.getNodeName(), switched);
            j.assertLogContains("returned=switched-result", switched);
            j.assertLogContains("Requesting Jenkins node allocation for label 'second-node'", switched);

            WorkflowRun outside = build(j, """
                    def result = nodeIfCurrentDoesNotMatch('second-node') {
                        echo "outside-node=${env.NODE_NAME}"
                        return 'outside-result'
                    }
                    echo "returned=${result}"
                    """);
            j.assertBuildStatusSuccess(outside);
            j.assertLogContains("outside-node=" + second.getNodeName(), outside);
            j.assertLogContains("returned=outside-result", outside);
            j.assertLogContains("Requesting Jenkins node allocation for label 'second-node'", outside);
        });
    }

    @Test
    void bodyFailuresAndInterruptionsArePreserved() throws Throwable {
        sessions.then(j -> {
            DumbSlave node = j.createOnlineSlave(Label.get("failure-node"));
            WorkflowRun failed = build(j, ("""
                    node('%s') {
                        nodeIfCurrentDoesNotMatch('failure-node') {
                            error 'body-failed'
                        }
                    }
                    """).formatted(node.getNodeName()));
            j.assertBuildStatus(Result.FAILURE, failed);
            j.assertLogContains("body-failed", failed);

            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "interrupted-node-if-current");
            job.setDefinition(new CpsFlowDefinition(("""
                    node('%s') {
                        nodeIfCurrentDoesNotMatch('failure-node') {
                            echo 'interruptible-body-started'
                            sleep 60
                            echo 'interruptible-body-finished'
                        }
                    }
                    """).formatted(node.getNodeName()), true));
            WorkflowRun interrupted = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("interruptible-body-started", interrupted);
            interrupted.doStop();
            j.waitForCompletion(interrupted);

            j.assertBuildStatus(Result.ABORTED, interrupted);
            j.assertLogNotContains("interruptible-body-finished", interrupted);
        });
    }

    @Test
    void inPlaceBodySurvivesControllerRestart() throws Throwable {
        sessions.then(j -> {
            j.jenkins.setNumExecutors(1);
            j.jenkins.setLabelString("restart-node");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "restart-node-if-current");
            job.setDefinition(new CpsFlowDefinition("""
                    node('restart-node') {
                        nodeIfCurrentDoesNotMatch('restart-node') {
                            echo 'before-node-if-current-restart'
                            sleep 8
                            echo "after-node-if-current-restart=${env.NODE_NAME}"
                        }
                    }
                    """, true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("before-node-if-current-restart", run);
        });
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.getItemByFullName("restart-node-if-current", WorkflowJob.class);
            assertNotNull(job);
            WorkflowRun run = requireNonNull(job.getLastBuild());
            j.waitForCompletion(run);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("after-node-if-current-restart=", run);
        });
    }

    private static WorkflowRun build(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return requireNonNull(job.scheduleBuild2(0)).get(30, TimeUnit.SECONDS);
    }

    private static void assertSingleNodeAllocation(WorkflowRun run) throws Exception {
        String log = JenkinsRule.getLog(run);
        assertTrue(log.indexOf("Running on ") == log.lastIndexOf("Running on "), log);
    }
}
