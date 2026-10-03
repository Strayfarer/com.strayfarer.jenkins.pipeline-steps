package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.AbortException;
import hudson.Functions;
import hudson.model.Result;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class InsideDockerContainerStepTest {

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void nestedScopesRouteLexicallyAndRestoreAfterFailure() throws Throwable {
        sessions.then(j -> {
            Path log = installFakeDocker(j);
            WorkflowRun run = build(j, """
                    node {
                        insideDockerContainer('outer') {
                            exec 'echo outer-command'
                            try {
                                insideDockerContainer('inner') {
                                    exec 'exit 5'
                                }
                            } catch (Exception expected) {
                                exec 'echo restored-outer'
                            }
                        }
                        exec 'echo restored-host'
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("outer-command", run);
            j.assertLogContains("restored-outer", run);
            j.assertLogContains("restored-host", run);
            List<String> lines = Files.readAllLines(log);
            assertEquals(2, countInspections(lines, "|outer"));
            assertEquals(1, countInspections(lines, "|inner"));
            assertTrue(Files.readString(log).contains("{{.State.Running}} {{.Platform}}"), Files.readString(log));
            assertEquals(List.of("outer", "inner", "outer"), executions(lines));
        });
    }

    @Test
    void execStatusReturnsTheContainerProcessExitStatus() throws Throwable {
        sessions.then(j -> {
            installFakeDocker(j);
            WorkflowRun run = build(j, """
                    node {
                        insideDockerContainer('status') {
                            def status = execStatus 'exit 9'
                            echo "container-status=${status}"
                        }
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("container-status=9", run);
        });
    }

    @Test
    void containerSelectionAndConnectionDeferInspectionUntilCommandExecution() throws Throwable {
        sessions.then(j -> {
            Path log = installFakeDocker(j);
            WorkflowRun run = build(j, """
                    node {
                        def container = connectToDockerContainer('missing')
                        insideDockerContainer('missing') {
                            echo 'missing-scope-entered'
                        }
                        echo 'missing-handle-created'
                        try {
                            container.exec 'echo should-not-run'
                            error 'missing container command unexpectedly succeeded'
                        } catch (hudson.AbortException expected) {
                            echo "missing-command=${expected.message}"
                        }
                        insideDockerContainer('missing') {
                            try {
                                exec 'echo should-not-run'
                                error 'missing scoped command unexpectedly succeeded'
                            } catch (hudson.AbortException expected) {
                                echo "missing-scoped-command=${expected.message}"
                            }
                        }
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("missing-scope-entered", run);
            j.assertLogContains("missing-handle-created", run);
            j.assertLogContains("Docker container 'missing' does not exist or cannot be inspected", run);
            assertEquals(2, countInspections(Files.readAllLines(log), "|missing"));
            assertEquals(List.of(), executions(Files.readAllLines(log)));
        });
    }

    @Test
    void connectedContainerHasIndependentCommandMethodsAndResolvesEnvironmentAtExecution() throws Throwable {
        sessions.then(j -> {
            Path log = installFakeDocker(j);
            WorkflowRun run = build(j, """
                    node {
                        env.FORWARDED_VALUE = 'before'
                        def container = connectToDockerContainer(
                            container: 'connected', environment: ['FORWARDED_VALUE', '', 'FORWARDED_VALUE'])
                        env.FORWARDED_VALUE = 'after'
                        exec 'echo host-before'
                        container.exec 'echo connected-exec'
                        assert container.execStatus('exit 7') == 7
                        assert container.execStdout(script: 'echo connected-stdout') == 'connected-stdout'
                        insideDockerContainer('outer') {
                            container.exec 'echo connected-inside-outer'
                            exec 'echo outer-exec'
                        }
                        exec 'echo host-after'
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("host-before", run);
            j.assertLogContains("host-after", run);
            j.assertLogContains("connected-stdout", run);
            String dockerLog = Files.readString(log);
            assertEquals(
                    List.of("connected", "connected", "connected", "connected", "outer"),
                    executions(Files.readAllLines(log)));
            assertEquals(4, countInspections(Files.readAllLines(log), "|connected"));
            assertTrue(dockerLog.contains("ENV|FORWARDED_VALUE|after"), dockerLog);
            assertFalse(dockerLog.contains("ENV|FORWARDED_VALUE|before"), dockerLog);
        });
    }

    @Test
    void connectedContainerHandleSurvivesAControllerRestart() throws Throwable {
        sessions.then(j -> {
            installFakeDocker(j);
            String command = Functions.isWindows()
                    ? "Write-Output 'connected-before'; Start-Sleep -Seconds 8; Write-Output 'connected-after'"
                    : "echo connected-before; sleep 8; echo connected-after";
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "connected-restart");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        def container = connectToDockerContainer('restart-container')
                        def output = container.execStdout "%s"
                        echo "connected-output=${output.contains('connected-after')}"
                        container.exec 'echo connected-later'
                    }
                    """.formatted(command), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("connected-before", run);
        });
        sessions.then(j -> {
            WorkflowJob job = j.jenkins.getItemByFullName("connected-restart", WorkflowJob.class);
            assertNotNull(job);
            WorkflowRun run = j.waitForCompletion(requireNonNull(job.getLastBuild()));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("connected-after", run);
            j.assertLogContains("connected-output=true", run);
            j.assertLogContains("connected-later", run);
        });
    }

    @Test
    void containerCommandsKeepBookkeepingOutsideTheCurrentDirectory() throws Throwable {
        sessions.then(j -> {
            installFakeDocker(j);
            String command = Functions.isWindows()
                    ? "if (Get-ChildItem -Force -Filter '.pipeline-*') { Write-Output 'polluted' } else { Write-Output 'clean' }"
                    : "if find . -maxdepth 1 -name '.pipeline-*' -print -quit | grep -q .; then echo polluted; else echo clean; fi";
            WorkflowRun run = build(j, """
                    node {
                        dir('repository') {
                            withEnv(["WORKSPACE_TMP=${pwd()}/missing-temp"]) {
                                insideDockerContainer('bookkeeping') {
                                    def value = execStdout "%s"
                                    echo "container-bookkeeping=${value}"
                                }
                            }
                        }
                    }
                    """.formatted(command));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("container-bookkeeping=clean", run);
            j.assertLogNotContains("container-bookkeeping=polluted", run);
        });
    }

    @Test
    void environmentAllowlistIsNormalizedAndValuesStayOutOfArguments() throws Throwable {
        sessions.then(j -> {
            Path log = installFakeDocker(j);
            WorkflowRun run = build(j, """
                    node {
                        env.FORWARDED_VALUE = 'resolved-at-execution'
                        insideDockerContainer(
                            container: 'environment',
                            environment: ['FORWARDED_VALUE', '', 'FORWARDED_VALUE']
                        ) {
                            exec 'echo environment-ran'
                        }
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            String dockerLog = Files.readString(log);
            assertTrue(dockerLog.contains("ENV|FORWARDED_VALUE|resolved-at-execution"), dockerLog);
            assertEquals(1, forwardedEnvironmentOccurrences(dockerLog));
            for (String line : Files.readAllLines(log)) {
                if (line.startsWith("ARGS")) {
                    assertFalse(line.contains("resolved-at-execution"), line);
                }
            }
            j.assertLogNotContains("resolved-at-execution", run);
        });
    }

    @Test
    void abortStopsTheContainerProcessAndPreservesInterruption() throws Throwable {
        sessions.then(j -> {
            Path log = installFakeDocker(j);
            String command = Functions.isWindows()
                    ? "Write-Output 'docker-started'; Start-Sleep -Seconds 60"
                    : "echo docker-started; sleep 60";
            WorkflowJob job = j.jenkins.createProject(WorkflowJob.class, "abort");
            job.setDefinition(new CpsFlowDefinition("""
                    node {
                        insideDockerContainer('interrupted') {
                            exec "%s"
                        }
                    }
                    """.formatted(command), true));
            WorkflowRun run = requireNonNull(job.scheduleBuild2(0)).waitForStart();
            j.waitForMessage("docker-started", run);

            run.doStop();
            j.waitForCompletion(run);

            j.assertBuildStatus(Result.ABORTED, run);
            String dockerLog = Files.readString(log);
            if (!Functions.isWindows()) {
                assertTrue(dockerLog.contains("KILL|interrupted"), dockerLog);
            }
        });
    }

    @Test
    void validatesEnvironmentNamesAndInspectionResults() throws Exception {
        InsideDockerContainerStep step = new InsideDockerContainerStep("container");
        step.setEnvironment(List.of("VALID", "", "VALID", "ALSO_VALID_2"));
        assertEquals(List.of("VALID", "ALSO_VALID_2"), step.getEnvironment());
        assertThrows(IllegalArgumentException.class, () -> step.setEnvironment(List.of("VALID", "NOT-VALID")));

        assertThrows(AbortException.class, () -> DockerContext.osFromInspection("missing", "1\n"));
        assertThrows(AbortException.class, () -> DockerContext.osFromInspection("stopped", "0\nfalse linux\n"));
        assertThrows(AbortException.class, () -> DockerContext.osFromInspection("unsupported", "0\ntrue plan9\n"));
        DockerContext context = new DockerContext("ready", List.of("VALUE"));
        assertEquals("ready", context.container());
        assertEquals("linux", DockerContext.osFromInspection("ready", "0\ntrue linux\n"));
    }

    @Test
    void posixCleanupResolvesDockerThroughTheAgentPath() {
        List<String> command = List.of("docker", "exec", "container with ' quote");

        assertEquals(
                List.of("/bin/sh", "-c", "exec 'docker' 'exec' 'container with '\"'\"' quote'"),
                DockerProcessDurableTask.launcherCommand(command, true));
        assertEquals(command, DockerProcessDurableTask.launcherCommand(command, false));
    }

    private Path installFakeDocker(JenkinsRule j) throws IOException {
        Path tools = temporaryDirectory.resolve("tools");
        Files.createDirectories(tools);
        copyResource("fake-docker.ps1", tools.resolve("fake-docker.ps1"));
        if (Functions.isWindows()) {
            copyResource("fake-docker.cmd", tools.resolve("docker.cmd"));
        } else {
            Path docker = tools.resolve("docker");
            copyResource("fake-docker", docker);
            Files.setPosixFilePermissions(
                    docker,
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE));
        }
        Path log = temporaryDirectory.resolve("docker.log");
        Files.deleteIfExists(log);
        Files.createFile(log);
        j.jenkins
                .getGlobalNodeProperties()
                .add(new EnvironmentVariablesNodeProperty(
                        new EnvironmentVariablesNodeProperty.Entry("PATH+FAKE_DOCKER", tools.toString()),
                        new EnvironmentVariablesNodeProperty.Entry("FAKE_DOCKER_LOG", log.toString())));
        return log;
    }

    private static void copyResource(String name, Path target) throws IOException {
        try (InputStream stream =
                InsideDockerContainerStepTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing test resource " + name);
            }
            Files.copy(stream, target);
        }
    }

    private static WorkflowRun build(JenkinsRule j, String script) throws Exception {
        WorkflowJob job = j.jenkins.createProject(
                WorkflowJob.class, "test-" + j.jenkins.getItems().size());
        job.setDefinition(new CpsFlowDefinition(script, true));
        return requireNonNull(job.scheduleBuild2(0)).get();
    }

    private static int countInspections(List<String> lines, String suffix) {
        return (int) lines.stream()
                .filter(line -> line.startsWith("ARGS|inspect") && line.endsWith(suffix))
                .count();
    }

    private static List<String> executions(List<String> lines) {
        return lines.stream()
                .filter(line -> line.startsWith("EXEC|"))
                .map(line -> line.substring("EXEC|".length()))
                .toList();
    }

    private static int forwardedEnvironmentOccurrences(String text) {
        String needle = "--env|FORWARDED_VALUE";
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
