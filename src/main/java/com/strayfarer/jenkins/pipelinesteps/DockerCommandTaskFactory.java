package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jenkinsci.plugins.durabletask.DurableTask;

final class DockerCommandTaskFactory {

    private static final String LINUX_SUPERVISOR = "echo $$ > \"$1\"; exec /bin/sh -c \"$2\" \"$3\"";

    private DockerCommandTaskFactory() {}

    static DurableTask task(
            DockerContext docker,
            String script,
            FilePath workspace,
            Launcher launcher,
            EnvVars environment,
            boolean captureStdout)
            throws IOException, InterruptedException {
        String token = "pipeline-exec-" + UUID.randomUUID();
        String pidFile = ".pipeline-docker-pid-" + UUID.randomUUID();
        String containerPidFile = containerPath(workspace.getRemote(), pidFile, docker.os());

        List<String> arguments = new ArrayList<>();
        arguments.add("docker");
        arguments.add("exec");
        arguments.add("--workdir");
        arguments.add(workspace.getRemote());
        for (String name : docker.environment()) {
            arguments.add("--env");
            arguments.add(name);
        }
        arguments.add("--");
        arguments.add(docker.container());
        if ("linux".equals(docker.os())) {
            arguments.addAll(List.of(
                    "setsid",
                    "/bin/sh",
                    "-c",
                    LINUX_SUPERVISOR,
                    "pipeline-supervisor",
                    containerPidFile,
                    script,
                    token));
        } else {
            arguments.addAll(List.of(
                    "pwsh",
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-EncodedCommand",
                    encodedWindowsSupervisor(containerPidFile, script)));
        }

        String hostScript = launcher.isUnix()
                ? "exec " + joinPosix(arguments)
                : "& " + joinPowerShell(arguments) + "\r\nexit $LASTEXITCODE";
        DurableTask hostTask = CommandTaskFactory.nativeTask(hostScript, launcher, environment, captureStdout);
        return new DockerProcessDurableTask(
                hostTask, docker.container(), docker.os(), pidFile, containerPidFile, token);
    }

    private static String containerPath(String workspace, String file, String os) {
        String separator = "windows".equals(os) ? "\\" : "/";
        return workspace.endsWith("/") || workspace.endsWith("\\") ? workspace + file : workspace + separator + file;
    }

    static String joinPosix(List<String> arguments) {
        return arguments.stream()
                .map(DockerCommandTaskFactory::quotePosix)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    private static String quotePosix(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String joinPowerShell(List<String> arguments) {
        return arguments.stream()
                .map(DockerCommandTaskFactory::quotePowerShell)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String encodedWindowsSupervisor(String pidFile, String script) {
        String pidFileBase64 = Base64.getEncoder().encodeToString(pidFile.getBytes(StandardCharsets.UTF_8));
        String scriptBase64 = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String supervisor = "$pidFile = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"
                + pidFileBase64
                + "')); $script = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"
                + scriptBase64
                + "')); $PID | Set-Content -LiteralPath $pidFile -NoNewline -Encoding ascii; "
                + "& ([scriptblock]::Create($script)); exit $LASTEXITCODE";
        return Base64.getEncoder().encodeToString(supervisor.getBytes(StandardCharsets.UTF_16LE));
    }
}
