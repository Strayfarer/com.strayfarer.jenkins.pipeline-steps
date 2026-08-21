package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Functions;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.structs.describable.DescribableParameter;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class CommandStepsTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void execStatusStreamsOutputAndReturnsNonzeroStatus() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "Write-Output 'status-stdout'; [Console]::Error.WriteLine('status-stderr'); exit 7",
                    "echo status-stdout; echo status-stderr >&2; exit 7");
            WorkflowRun run = build(j, """
                    node {
                        def status = execStatus "%s"
                        exec "echo returned-status=${status}"
                    }
                    """.formatted(command));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("status-stdout", run);
            j.assertLogContains("status-stderr", run);
            j.assertLogContains("returned-status=7", run);
        });
    }

    @Test
    void execStdoutStreamsWhileRunningThenReturnsTrimmedStdout() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "Write-Output 'stdout-early'; Start-Sleep -Seconds 4; Write-Output '  value  '; [Console]::Error.WriteLine('stderr-only')",
                    "echo stdout-early; sleep 4; printf '  value  \\n'; echo stderr-only >&2");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "stdout");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        def value = execStdout "%s"
                        exec "echo 'returned=[${value}]'"
                        exec "echo stderr-returned=${value.contains('stderr-only')}"
                    }
                    """.formatted(command), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();

            j.waitForMessage("stdout-early", run);
            assertTrue(run.isBuilding(), JenkinsRule.getLog(run));
            j.waitForCompletion(run);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("stderr-only", run);
            j.assertLogContains("returned=[stdout-early", run);
            j.assertLogContains("  value]", run);
            j.assertLogContains("stderr-returned=false", run);
        });
    }

    @Test
    void execStdoutKeepsBookkeepingOutsideTheCurrentDirectoryAndIgnoresStaleWorkspaceTmp() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "if (Get-ChildItem -Force -Filter '.pipeline-*') { Write-Output 'polluted' } else { Write-Output 'clean' }",
                    "if find . -maxdepth 1 -name '.pipeline-*' -print -quit | grep -q .; then echo polluted; else echo clean; fi");
            WorkflowRun run = build(j, """
                    node {
                        dir('repository') {
                            withEnv(["WORKSPACE_TMP=${pwd()}/missing-temp"]) {
                                def value = execStdout "%s"
                                echo "workspace-bookkeeping=${value}"
                            }
                        }
                    }
                    """.formatted(command));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("workspace-bookkeeping=clean", run);
            j.assertLogNotContains("workspace-bookkeeping=polluted", run);
        });
    }

    @Test
    void execStdoutCaptureSurvivesAControllerRestart() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "Write-Output 'capture-before'; Start-Sleep -Seconds 8; Write-Output 'capture-after'",
                    "echo capture-before; sleep 8; echo capture-after");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "stdout-restart");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        def value = execStdout "%s"
                        exec "echo returned-before=${value.contains('capture-before')}"
                        exec "echo returned-after=${value.contains('capture-after')}"
                    }
                    """.formatted(command), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("capture-before", run);
        });
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.getItemByFullName("stdout-restart", WorkflowJob.class);
            assertNotNull(job);
            WorkflowRun lastBuild = job.getLastBuild();
            assertNotNull(lastBuild);
            WorkflowRun run = j.waitForCompletion(lastBuild);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("capture-before", run);
            j.assertLogContains("capture-after", run);
            j.assertLogContains("returned-before=true", run);
            j.assertLogContains("returned-after=true", run);
        });
    }

    @Test
    void execStdoutTranscodesCapturedOutputFromTheConfiguredEncoding() throws Throwable {
        Assumptions.assumeFalse(Functions.isWindows());
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    node {
                        def value = execStdout script: "printf '\\\\334ber\\\\n'", encoding: 'ISO-8859-1'
                        echo "encoded=[${value}]"
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("encoded=[Über]", run);
        });
    }

    @Test
    void commandStepsExposeTheSameDocumentedOptions() {
        assertEquals(Set.of("script", "echoScript", "encoding"), parameters(ExecStatusStep.class));
        assertEquals(Set.of("script", "echoScript", "encoding"), parameters(ExecStdoutStep.class));

        ExecStatusStep status = new ExecStatusStep("status-command");
        ExecStdoutStep stdout = new ExecStdoutStep("stdout-command");
        assertEquals("status-command", status.getScript());
        assertEquals("stdout-command", stdout.getScript());
        assertEquals("UTF-8", status.getEncoding());
        assertEquals("UTF-8", stdout.getEncoding());
        assertFalse(status.isEchoScript());
        assertFalse(stdout.isEchoScript());
    }

    @Test
    void windowsShellPrefersPwshAndFallsBackToWindowsPowerShell() {
        assertEquals("pwsh", CommandTaskFactory.selectWindowsShell(command -> true));
        assertEquals("powershell", CommandTaskFactory.selectWindowsShell(command -> false));
    }

    private static Set<String> parameters(Class<?> type) {
        return new DescribableModel<>(type)
                .getParameters().stream().map(DescribableParameter::getName).collect(Collectors.toSet());
    }

    private static WorkflowRun build(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return requireNonNull(job.scheduleBuild2(0)).get();
    }

    private static String nativeCommand(String windows, String unix) {
        return Functions.isWindows() ? windows : unix;
    }
}
