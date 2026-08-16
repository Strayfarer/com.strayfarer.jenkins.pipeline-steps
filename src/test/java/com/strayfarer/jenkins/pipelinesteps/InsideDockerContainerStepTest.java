package com.strayfarer.jenkins.pipelinesteps;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            String metadata = Functions.isWindows()
                    ? "echo metadata=$env:PIPELINE_DOCKER_CONTAINER_NAME"
                    : "echo metadata=$PIPELINE_DOCKER_CONTAINER_NAME";
            WorkflowRun run = build(j, """
                    node {
                        insideDockerContainer('outer') {
                            exec '%s'
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
                    """.formatted(metadata));

            j.assertBuildStatusSuccess(run);
            j.assertLogContains("metadata=outer", run);
            j.assertLogContains("restored-outer", run);
            j.assertLogContains("restored-host", run);
            List<String> lines = Files.readAllLines(log);
            assertEquals(1, count(lines, "ARGS|inspect", "|outer"));
            assertEquals(1, count(lines, "ARGS|inspect", "|inner"));
            assertEquals(List.of("outer", "inner", "outer"), executions(lines));
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
            assertEquals(1, occurrences(dockerLog, "--env|FORWARDED_VALUE"));
            for (String line : Files.readAllLines(log)) {
                if (line.startsWith("ARGS")) {
                    assertTrue(!line.contains("resolved-at-execution"), line);
                }
            }
            j.assertLogNotContains("resolved-at-execution", run);
        });
    }

    @Test
    void metadataVariablesAloneDoNotEnableRouting() throws Throwable {
        sessions.then(j -> {
            Path log = installFakeDocker(j);
            WorkflowRun run = build(j, """
                    node {
                        env.PIPELINE_DOCKER_CONTAINER_NAME = 'forged'
                        env.PIPELINE_DOCKER_CONTAINER_ID = 'forged-id'
                        env.PIPELINE_DOCKER_CONTAINER_OS = 'linux'
                        exec 'echo host-command'
                    }
                    """);

            j.assertBuildStatusSuccess(run);
            assertEquals(List.of(), executions(Files.readAllLines(log)));
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
            WorkflowRun run = job.scheduleBuild2(0).waitForStart();
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

        assertThrows(AbortException.class, () -> DockerContext.fromInspection("missing", List.of(), "1\n"));
        assertThrows(
                AbortException.class, () -> DockerContext.fromInspection("stopped", List.of(), "0\nid false linux\n"));
        assertThrows(
                AbortException.class,
                () -> DockerContext.fromInspection("unsupported", List.of(), "0\nid true plan9\n"));
        DockerContext context = DockerContext.fromInspection("ready", List.of("VALUE"), "0\nid true linux\n");
        assertEquals("ready", context.container());
        assertEquals("id", context.id());
        assertEquals("linux", context.os());
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
        return job.scheduleBuild2(0).get();
    }

    private static int count(List<String> lines, String prefix, String suffix) {
        return (int) lines.stream()
                .filter(line -> line.startsWith(prefix) && line.endsWith(suffix))
                .count();
    }

    private static List<String> executions(List<String> lines) {
        return lines.stream()
                .filter(line -> line.startsWith("EXEC|"))
                .map(line -> line.substring("EXEC|".length()))
                .toList();
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
