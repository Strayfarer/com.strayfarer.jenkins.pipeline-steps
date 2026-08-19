package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class IsWindowsGlobalVariableTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void negatesIsUnixWithoutAddingAPipelineStepNode() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    node {
                        def unix = isUnix()
                        def windows = isWindows()
                        if (windows == unix) {
                            error "isWindows=${windows}, isUnix=${unix}"
                        }
                        echo "windows=${windows}"
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogNotContains("[Pipeline] isWindows", run);
            List<String> functions = stepFunctions(run);
            assertTrue(functions.contains("isUnix"));
            assertFalse(functions.contains("isWindows"));
        });
    }

    @Test
    void requiresANodeContextLikeIsUnix() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, "isWindows()\n");

            j.assertBuildStatus(Result.FAILURE, run);
            j.assertLogContains("Required context class hudson.Launcher is missing", run);
        });
    }

    private static WorkflowRun build(org.jvnet.hudson.test.JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return requireNonNull(job.scheduleBuild2(0)).get();
    }

    private static List<String> stepFunctions(WorkflowRun run) throws IOException {
        List<String> functions = new ArrayList<>();
        for (FlowNode node : new FlowGraphWalker(requireNonNull(run.getExecution()))) {
            if (node instanceof StepNode stepNode) {
                functions.add(stepNode.getDescriptor().getFunctionName());
            }
        }
        return functions;
    }
}
