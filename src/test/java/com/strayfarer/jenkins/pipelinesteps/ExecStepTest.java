package com.strayfarer.jenkins.pipelinesteps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Functions;
import hudson.model.Result;
import java.util.Set;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.PowershellScript;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class ExecStepTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @Test
    void shorthandStreamsOutputAndReturnsNull() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "Write-Output 'stdout-marker'; [Console]::Error.WriteLine('stderr-marker')",
                    "echo stdout-marker; echo stderr-marker >&2");
            WorkflowRun run = build(j, """
                    node {
                        def value = exec "%s"
                        exec "echo 'null-result=${value == null}'"
                    }
                    """.formatted(command));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("stdout-marker", run);
            j.assertLogContains("stderr-marker", run);
            j.assertLogContains("null-result=true", run);
        });
    }

    @Test
    void mapFormSupportsUtf8AndDoesNotEchoScriptByDefault() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand("Write-Output ([string][char]0x00DC + 'ber')", "printf '%s' 'Über'");
            WorkflowRun run = build(j, """
                    node {
                        exec script: "%s", encoding: 'UTF-8'
                    }
                    """.formatted(command));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("Über", run);
            j.assertLogNotContains(command, run);
        });
    }

    @Test
    void echoScriptPrintsTheCommandBeforeItsOutput() throws Throwable {
        sessions.then(j -> {
            String command = "echo command-output";
            WorkflowRun run = build(j, """
                    node {
                        exec script: "%s", echoScript: true
                    }
                    """.formatted(command));

            j.assertBuildStatusSuccess(run);
            String log = JenkinsRule.getLog(run);
            int echoedCommand = log.indexOf(command);
            int commandOutput = log.indexOf("command-output", echoedCommand + command.length());
            assertTrue(echoedCommand >= 0, log);
            assertTrue(commandOutput > echoedCommand, log);
        });
    }

    @Test
    void nonzeroExitFailsTheStep() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    node {
                        exec 'exit 7'
                    }
                    """);

            j.assertBuildStatus(Result.FAILURE, run);
            j.assertLogContains("script returned exit code 7", run);
        });
    }

    @Test
    void requiresAWorkspace() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, "exec \"echo never-runs\"");

            j.assertBuildStatus(Result.FAILURE, run);
            j.assertLogContains("Required context class hudson.FilePath is missing", run);
            j.assertLogNotContains("never-runs", run);
        });
    }

    @Test
    void survivesAControllerRestart() throws Throwable {
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "restart");
            String command = nativeCommand(
                    "Write-Output 'before-restart'; Start-Sleep -Seconds 8; Write-Output 'after-restart'",
                    "echo before-restart; sleep 8; echo after-restart");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        exec "%s"
                    }
                    """.formatted(command), true));
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.waitForMessage("before-restart", run);
        });
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.getItemByFullName("restart", WorkflowJob.class);
            WorkflowRun run = j.waitForCompletion(job.getLastBuild());

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("before-restart", run);
            j.assertLogContains("after-restart", run);
        });
    }

    @Test
    void abortPreservesTheInterruptedResult() throws Throwable {
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "abort");
            String command = nativeCommand(
                    "Write-Output 'started'; Start-Sleep -Seconds 60; Write-Output 'should-not-finish'",
                    "echo started; sleep 60; echo should-not-finish");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        exec "%s"
                    }
                    """.formatted(command), true));
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
            j.waitForMessage("started", run);

            run.doStop();
            j.waitForCompletion(run);

            j.assertBuildStatus(Result.ABORTED, run);
            j.assertLogNotContains("should-not-finish", run);
        });
    }

    @Test
    void descriptorExposesOnlyTheDocumentedOptions() {
        ExecStep step = new ExecStep("Write-Output 'hello'");

        assertEquals("Write-Output 'hello'", step.getScript());
        assertFalse(step.isEchoScript());
        assertEquals("UTF-8", step.getEncoding());
        step.setEchoScript(true);
        step.setEncoding("UTF-16LE");
        assertTrue(step.isEchoScript());
        assertEquals("UTF-16LE", step.getEncoding());

        ExecStep.DescriptorImpl descriptor = new ExecStep.DescriptorImpl();
        assertEquals("exec", descriptor.getFunctionName());
        assertEquals("Execute command", descriptor.getDisplayName());
        assertEquals("command", descriptor.argumentsToString(java.util.Map.of("script", "command")));
        assertNotNull(descriptor.getRequiredContext());

        Set<String> parameters = new DescribableModel<>(ExecStep.class)
                .getParameters().stream().map(parameter -> parameter.getName()).collect(Collectors.toSet());
        assertEquals(Set.of("script", "echoScript", "encoding"), parameters);
    }

    @Test
    void selectsTheNativeShellWithoutShellTracing() {
        ExecStep step = new ExecStep("echo hello");

        DurableTask unixTask = step.task(true);
        assertTrue(unixTask instanceof BourneShellScript);
        assertEquals("#!/bin/sh\necho hello", ((BourneShellScript) unixTask).getScript());

        DurableTask windowsTask = step.task(false);
        assertTrue(windowsTask instanceof PowershellScript);
        assertEquals("pwsh", ((PowershellScript) windowsTask).getPowershellBinary());
        assertEquals("echo hello", ((PowershellScript) windowsTask).getScript());
    }

    private static WorkflowRun build(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job.scheduleBuild2(0).get();
    }

    private static String nativeCommand(String windows, String unix) {
        return Functions.isWindows() ? windows : unix;
    }
}
