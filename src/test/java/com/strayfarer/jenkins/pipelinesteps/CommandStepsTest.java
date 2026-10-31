package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.Functions;
import hudson.model.Result;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
                        def value = execStdout(script: "%s", stdout: 'restart/output.txt', stderr: 'restart/output.txt')
                        assert readFile('restart/output.txt').trim() == value
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
                        def fileValue = execStdout(script: "printf '\\\\334ber\\\\n'", encoding: 'ISO-8859-1', stdout: 'encoded/out.txt')
                        assert fileValue == value
                        assert readFile(file: 'encoded/out.txt', encoding: 'ISO-8859-1').trim() == value
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("encoded=[Über]", run);
        });
    }

    @Test
    void outputFilesPreserveUtf8AndSupportLongScripts() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    node {
                        def script = ('# long script padding\\n' * 3000) + "echo 'Grüße 🌍'"
                        def value = execStdout(script: script, stdout: 'unicode/output.txt')
                        assert value == 'Grüße 🌍'
                        assert readFile(file: 'unicode/output.txt', encoding: 'UTF-8').trim() == value
                    }
                    """);
            j.assertBuildStatusSuccess(run);
        });
    }

    @Test
    void commandStepsExposeTheSameDocumentedOptions() {
        assertEquals(Set.of("script", "echoScript", "encoding", "stdout", "stderr"), parameters(ExecStep.class));
        assertEquals(Set.of("script", "echoScript", "encoding", "stdout", "stderr"), parameters(ExecStatusStep.class));
        assertEquals(Set.of("script", "echoScript", "encoding", "stdout", "stderr"), parameters(ExecStdoutStep.class));

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
    void outputFilesStreamBeforeCompletionAndPreserveWhitespaceOnNonzeroExit() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "[Console]::Out.Write('  early-out  '); [Console]::Error.Write('  early-err  '); while (!(Test-Path -LiteralPath release)) { Start-Sleep -Milliseconds 100 }; exit 7",
                    "printf '  early-out  '; printf '  early-err  ' >&2; while [ ! -f release ]; do sleep 1; done; exit 7");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "output-files");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        dir('repository') {
                            def status = timeout(time: 2, unit: 'MINUTES') {
                                execStatus(script: "%s", stdout: "nested dir/it's output.txt", stderr: 'other/deep/error.txt')
                            }
                            assert status == 7
                            assert readFile("nested dir/it's output.txt") == '  early-out  '
                            assert readFile('other/deep/error.txt') == '  early-err  '
                        }
                    }
                    """.formatted(command), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            FilePath workspace = requireNonNull(j.jenkins.getWorkspaceFor(job)).child("repository");
            // Console log readers can withhold partial lines until the command finishes.
            try {
                waitForFileOutput(workspace.child("nested dir/it's output.txt"), "  early-out  ");
                waitForFileOutput(workspace.child("other/deep/error.txt"), "  early-err  ");
                assertTrue(run.isBuilding(), JenkinsRule.getLog(run));
            } finally {
                workspace.child("release").write("continue", "UTF-8");
            }
            j.assertBuildStatusSuccess(j.waitForCompletion(run));
            j.assertLogContains("early-out", run);
            j.assertLogContains("early-err", run);
        });
    }

    @Test
    void sharedOutputFileMatchesConsoleAndStdoutReturnRemainsSeparate() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "[Console]::Out.Write('out-one|'); [Console]::Error.Write('err-one|'); [Console]::Out.Write('out-two|')",
                    "printf 'out-one|'; printf 'err-one|' >&2; printf 'out-two|'");
            WorkflowRun run = build(j, """
                    node {
                        writeFile(file: 'reports/combined.txt', text: 'stale')
                        def value = execStdout(script: "%s", stdout: 'reports/combined.txt', stderr: 'reports/./combined.txt')
                        assert value == 'out-one|out-two|'
                        def combined = readFile('reports/combined.txt')
                        assert combined.contains('out-one|') && combined.contains('out-two|') && combined.contains('err-one|')
                        assert !combined.contains('stale')
                        writeFile(file: 'console-comparison.txt', text: combined)
                    }
                    """.formatted(command));
            j.assertBuildStatusSuccess(run);
            FilePath workspace = requireNonNull(j.jenkins.getWorkspaceFor(run.getParent()));
            assertTrue(
                    JenkinsRule.getLog(run)
                            .contains(workspace.child("console-comparison.txt").readToString()),
                    JenkinsRule.getLog(run));
        });
    }

    @Test
    void execStillFailsAfterWritingBothFilesAndSingleStreamOptionsWork() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "Write-Output 'failure-out'; [Console]::Error.WriteLine('failure-err'); exit 9",
                    "echo failure-out; echo failure-err >&2; exit 9");
            WorkflowRun run = build(j, """
                    node {
                        try {
                            exec(script: "%s", stdout: 'failed/out.txt', stderr: 'failed/err.txt')
                        } finally {
                            assert readFile('failed/out.txt').trim() == 'failure-out'
                            assert readFile('failed/err.txt').trim() == 'failure-err'
                        }
                    }
                    """.formatted(command));
            j.assertBuildStatus(Result.FAILURE, run);
            j.assertLogContains("script returned exit code 9", run);
            WorkflowRun single = build(j, """
                    node {
                        exec(script: 'echo only-out', stdout: 'out.txt')
                        assert readFile('out.txt').trim() == 'only-out'
                        exec(script: 'echo captured', stderr: 'err.txt')
                        assert readFile('err.txt') == ''
                        def value = execStdout(script: 'echo returned', stderr: 'another/err.txt')
                        assert value == 'returned'
                        assert readFile('another/err.txt') == ''
                    }
                    """);
            j.assertBuildStatusSuccess(single);
        });
    }

    @Test
    void abortStopsFileStreamingAndPreservesPartialOutput() throws Throwable {
        sessions.then(j -> {
            String command = nativeCommand(
                    "Write-Output 'file-started'; Start-Sleep -Seconds 60; Write-Output 'file-should-not-finish'",
                    "echo file-started; sleep 60; echo file-should-not-finish");
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "file-abort");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        exec(script: "%s", stdout: 'partial/output.txt', stderr: 'partial/output.txt')
                    }
                    """.formatted(command), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("file-started", run);
            run.doStop();
            j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(run));
            FilePath workspace = requireNonNull(j.jenkins.getWorkspaceFor(job));
            assertEquals(
                    "file-started",
                    workspace.child("partial/output.txt").readToString().trim());
            j.assertLogNotContains("file-should-not-finish", run);
        });
    }

    @Test
    void emptyOutputPathsBehaveLikeOmittedOptions() throws Throwable {
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    node {
                        exec(script: 'echo plain-output', stdout: '', stderr: '')
                        assert execStatus(script: 'exit 7', stdout: null, stderr: '') == 7
                        assert execStdout(script: 'echo captured-output', stdout: '', stderr: null) == 'captured-output'
                    }
                    """);
            j.assertBuildStatusSuccess(run);
            j.assertLogContains("plain-output", run);
        });
    }

    @Test
    void outputFileErrorsFailEvenWhenReturningExitStatus() throws Throwable {
        sessions.then(j -> {
            for (String stream : Set.of("stdout", "stderr")) {
                WorkflowRun run = build(j, """
                    node {
                        dir('destination') {
                            writeFile(file: 'keep.txt', text: 'existing directory')
                        }
                        def options = [script: 'echo output']
                        options['%s'] = 'destination'
                        execStatus(options)
                        echo 'unexpectedly-continued'
                    }
                    """.formatted(stream));
                j.assertBuildStatus(Result.FAILURE, run);
                j.assertLogContains("Could not stream command output to the requested files", run);
                j.assertLogNotContains("unexpectedly-continued", run);
            }
        });
    }

    @Test
    void fileOptionsPreservePowerShellNonterminatingErrorBehavior() throws Throwable {
        Assumptions.assumeTrue(Functions.isWindows());
        sessions.then(j -> {
            WorkflowRun run = build(j, """
                    node {
                        def command = "Write-Error 'nonterminating-error'; Write-Output 'after-error'"
                        def original = execStatus(script: command)
                        def withFiles = execStatus(script: command, stdout: 'errors/out.txt', stderr: 'errors/err.txt')
                        assert withFiles == original
                        assert readFile('errors/out.txt').trim() == 'after-error'
                        assert readFile('errors/err.txt').contains('nonterminating-error')
                    }
                    """);
            j.assertBuildStatusSuccess(run);
        });
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

    private static void waitForFileOutput(FilePath file, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (file.exists() && expected.equals(file.readToString())) {
                return;
            }
            Thread.sleep(100);
        }
        assertTrue(file.exists(), "Output file was not created: " + file);
        assertEquals(expected, file.readToString(), "Output was not streamed before command completion");
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
