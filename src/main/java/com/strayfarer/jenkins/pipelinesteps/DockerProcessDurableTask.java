package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.Handler;

final class DockerProcessDurableTask extends DurableTask {

    private static final Set<String> DOCKER_ENVIRONMENT =
            Set.of("PATH", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH");

    private final DurableTask delegate;
    private final String container;
    private final String containerOs;
    private final String pidFile;
    private final String containerPidFile;
    private final String token;

    DockerProcessDurableTask(
            DurableTask delegate,
            String container,
            String containerOs,
            String pidFile,
            String containerPidFile,
            String token) {
        this.delegate = delegate;
        this.container = container;
        this.containerOs = containerOs;
        this.pidFile = pidFile;
        this.containerPidFile = containerPidFile;
        this.token = token;
    }

    static List<String> launcherCommand(List<String> command, boolean unix) {
        return unix ? List.of("/bin/sh", "-c", "exec " + DockerCommandTaskFactory.joinPosix(command)) : command;
    }

    @Override
    public void captureOutput() {
        delegate.captureOutput();
    }

    @Override
    public void charset(@NonNull Charset charset) {
        delegate.charset(charset);
    }

    @Override
    public void defaultCharset() {
        delegate.defaultCharset();
    }

    @Override
    public Controller launch(EnvVars environment, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        FilePath hostPidFile = WorkspaceTemporaryFiles.resolve(workspace, pidFile);
        WorkspaceTemporaryFiles.prepare(hostPidFile);
        EnvVars dockerEnvironment = new EnvVars();
        for (String name : DOCKER_ENVIRONMENT) {
            if (environment.containsKey(name)) {
                dockerEnvironment.put(name, environment.get(name));
            }
        }
        Controller controller = delegate.launch(environment, workspace, launcher, listener);
        return new DockerProcessController(
                controller, container, containerOs, pidFile, containerPidFile, token, dockerEnvironment);
    }

    private static final class DockerProcessController extends Controller {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Controller delegate;
        private final String container;
        private final String containerOs;
        private final String pidFile;
        private final String containerPidFile;
        private final String token;
        private final EnvVars dockerEnvironment;

        private DockerProcessController(
                Controller delegate,
                String container,
                String containerOs,
                String pidFile,
                String containerPidFile,
                String token,
                EnvVars dockerEnvironment) {
            this.delegate = delegate;
            this.container = container;
            this.containerOs = containerOs;
            this.pidFile = pidFile;
            this.containerPidFile = containerPidFile;
            this.token = token;
            this.dockerEnvironment = dockerEnvironment;
        }

        @Override
        public void watch(@NonNull FilePath workspace, @NonNull Handler handler, @NonNull TaskListener listener)
                throws IOException, InterruptedException, UnsupportedOperationException {
            delegate.watch(workspace, handler, listener);
        }

        @Override
        public boolean writeLog(FilePath workspace, OutputStream stream) throws IOException, InterruptedException {
            return delegate.writeLog(workspace, stream);
        }

        @Override
        public Integer exitStatus(FilePath workspace, Launcher launcher, TaskListener listener)
                throws IOException, InterruptedException {
            return delegate.exitStatus(workspace, launcher, listener);
        }

        @Override
        public @NonNull byte[] getOutput(@NonNull FilePath workspace, @NonNull Launcher launcher)
                throws IOException, InterruptedException {
            return delegate.getOutput(workspace, launcher);
        }

        @Override
        public void stop(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            try {
                List<String> command = launcherCommand(stopCommand(), launcher.isUnix());
                launcher.launch()
                        .cmds(command)
                        .envs(dockerEnvironment)
                        .pwd(workspace)
                        .quiet(true)
                        .stdout(OutputStream.nullOutputStream())
                        .stderr(OutputStream.nullOutputStream())
                        .join();
            } finally {
                delegate.stop(workspace, launcher);
            }
        }

        @Override
        public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            try {
                delegate.cleanup(workspace);
            } finally {
                WorkspaceTemporaryFiles.resolve(workspace, pidFile).delete();
            }
        }

        @Override
        public String getDiagnostics(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            return delegate.getDiagnostics(workspace, launcher);
        }

        private List<String> stopCommand() {
            List<String> command = new ArrayList<>(List.of("docker", "exec", "--", container));
            if ("linux".equals(containerOs)) {
                command.addAll(List.of(
                        "/bin/sh",
                        "-c",
                        "pid=$(cat \"$1\" 2>/dev/null || true); "
                                + "if [ -n \"$pid\" ]; then "
                                + "pkill -TERM -g \"$pid\" 2>/dev/null || true; sleep 1; "
                                + "pkill -KILL -g \"$pid\" 2>/dev/null || true; "
                                + "else pkill -TERM -f \"$2\" 2>/dev/null || true; fi",
                        "pipeline-kill",
                        containerPidFile,
                        token));
            } else {
                command.addAll(List.of(
                        "pwsh",
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-EncodedCommand",
                        encodedWindowsStop(containerPidFile)));
            }
            return command;
        }

        private static String encodedWindowsStop(String containerPidFile) {
            String pidFileBase64 =
                    Base64.getEncoder().encodeToString(containerPidFile.getBytes(StandardCharsets.UTF_8));
            String command = "$pidFile = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('"
                    + pidFileBase64
                    + "')); $processId = Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue; "
                    + "if ($processId) { & taskkill.exe /PID $processId /T /F | Out-Null }";
            return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        }
    }
}
