package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.model.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class WithEnvFileStepTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void defaultAndCustomFilesAreScopedAndRecordedOutsideTheConsole() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    node {
                        writeFile file: '.env', text: 'CRLF=default-value\\r\\nEMPTY=\\r\\n'
                        writeFile file: 'custom.env', text: 'CRLF=custom-value\\r\\nQUOTED="value # retained"\\r\\n'
                        env.CRLF = 'outer-value'

                        withEnvFile {
                            if (env.CRLF != 'default-value' || env.EMPTY != '') {
                                error 'default dotenv values were not applied'
                            }
                            withEnvFile('custom.env') {
                                if (env.CRLF != 'custom-value' || env.QUOTED != 'value # retained') {
                                    error 'custom dotenv values were not applied'
                                }
                            }
                            if (env.CRLF != 'default-value' || env.QUOTED != null) {
                                error 'nested dotenv scope was not restored'
                            }
                        }
                        if (env.CRLF != 'outer-value' || env.EMPTY != null) {
                            error 'outer environment was not restored'
                        }
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            assertEquals(
                    List.of(
                            "withEnvFile (.env): CRLF=default-value, EMPTY=",
                            "withEnvFile (custom.env): CRLF=custom-value, QUOTED=value # retained"),
                    stageLogLabels(run));
            j.assertLogNotContains("default-value", run);
            j.assertLogNotContains("custom-value", run);
            j.assertLogNotContains("value # retained", run);
        });
    }

    @Test
    void emptyFilesRunTheBodyAndMissingFilesFail() throws Throwable {
        sessions.then(j -> {
            WorkflowRun empty = build(j, """
                    node {
                        writeFile file: '.env', text: ''
                        withEnvFile {
                            echo 'empty-body-ran'
                        }
                    }
                    """);
            j.assertBuildStatusSuccess(empty);
            j.assertLogContains("empty-body-ran", empty);
            assertEquals(List.of("withEnvFile (.env): no variables"), stageLogLabels(empty));

            WorkflowRun missing = build(j, """
                    node {
                        withEnvFile('missing.env') {
                            error 'missing file body must not run'
                        }
                    }
                    """);
            j.assertBuildStatus(Result.FAILURE, missing);
            j.assertLogContains("missing.env", missing);
            j.assertLogNotContains("missing file body must not run", missing);
        });
    }

    @Test
    void environmentAndStageLogMetadataSurviveRestart() throws Throwable {
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "restart-with-env-file");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        writeFile file: '.env', text: 'RESTART_VALUE=persisted-value\\r\\n'
                        withEnvFile {
                            echo 'before-env-restart'
                            sleep 8
                            if (env.RESTART_VALUE != 'persisted-value') {
                                error 'dotenv value did not survive restart'
                            }
                            echo 'after-env-restart'
                        }
                    }
                    """, true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("before-env-restart", run);
        });
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.getItemByFullName("restart-with-env-file", WorkflowJob.class);
            WorkflowRun run = requireNonNull(requireNonNull(job).getLastBuild());
            j.waitForCompletion(run);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("after-env-restart", run);
            assertEquals(List.of("withEnvFile (.env): RESTART_VALUE=persisted-value"), stageLogLabels(run));
            j.assertLogNotContains("persisted-value", run);
        });
    }

    @Test
    void stageLogValuesEscapeControlCharacters() {
        assertEquals(
                "withEnvFile (.env): VALUE=one\\ntwo\\rthree\\tfour\\\\five",
                WithEnvFileStep.stageLogLabel(".env", Map.of("VALUE", "one\ntwo\rthree\tfour\\five")));
    }

    private static WorkflowRun build(org.jvnet.hudson.test.JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return requireNonNull(job.scheduleBuild2(0)).get();
    }

    private static List<String> stageLogLabels(WorkflowRun run) throws java.io.IOException {
        List<String> labels = new ArrayList<>();
        for (FlowNode node : new FlowGraphWalker(requireNonNull(run.getExecution()))) {
            LabelAction action = node.getAction(LabelAction.class);
            if (action != null && action.getDisplayName().startsWith("withEnvFile (")) {
                labels.add(action.getDisplayName());
            }
        }
        return labels.reversed();
    }
}
