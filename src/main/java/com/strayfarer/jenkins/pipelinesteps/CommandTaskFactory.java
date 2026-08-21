package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Predicate;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.PowershellScript;

final class CommandTaskFactory {

    private CommandTaskFactory() {}

    static DurableTask nativeTask(
            String script, FilePath workspace, Launcher launcher, EnvVars environment, boolean teeStdout)
            throws InterruptedException {
        if (launcher.isUnix()) {
            return task(script, workspace, true, null, teeStdout);
        }
        boolean pwshAvailable;
        try {
            pwshAvailable = pwshExists(launcher, environment);
        } catch (IOException exception) {
            pwshAvailable = false;
        }
        String shell = pwshAvailable ? "pwsh" : "powershell";
        return task(script, workspace, false, shell, teeStdout);
    }

    static DurableTask task(String script, FilePath workspace, boolean unix, String windowsShell, boolean teeStdout) {
        if (!teeStdout) {
            if (unix) {
                return new BourneShellScript("#!/bin/sh\n" + script);
            }
            PowershellScript powershellScript = new PowershellScript(script);
            powershellScript.setPowershellBinary(windowsShell);
            return powershellScript;
        }

        FilePath temporaryDirectory = WorkspaceTemporaryFiles.directory(workspace);
        String captureFile = temporaryDirectory
                .child(".pipeline-exec-stdout-" + UUID.randomUUID())
                .getRemote();
        DurableTask task;
        if (unix) {
            String statusFile = temporaryDirectory
                    .child(".pipeline-exec-status-" + UUID.randomUUID())
                    .getRemote();
            task = new BourneShellScript(teeUnixStdout(script, captureFile, statusFile));
        } else {
            PowershellScript powershellScript = new PowershellScript(teePowerShellStdout(script, captureFile));
            powershellScript.setPowershellBinary(windowsShell);
            task = powershellScript;
        }
        return new CapturedOutputDurableTask(task, captureFile, unix ? null : StandardCharsets.UTF_8);
    }

    static String selectWindowsShell(Predicate<String> available) {
        return available.test("pwsh") ? "pwsh" : "powershell";
    }

    private static boolean pwshExists(Launcher launcher, EnvVars environment) throws IOException, InterruptedException {
        return launcher.launch()
                        .cmds("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                        .envs(environment)
                        .quiet(true)
                        .stdout(OutputStream.nullOutputStream())
                        .stderr(OutputStream.nullOutputStream())
                        .join()
                == 0;
    }

    private static String teeUnixStdout(String script, String captureFile, String statusFile) {
        String delimiter = heredocDelimiter(script);
        return "#!/bin/sh\n"
                + "status_file="
                + quotePosix(statusFile)
                + "\n"
                + "trap 'rm -f \"$status_file\"' EXIT HUP INT TERM\n"
                + "{\n"
                + "/bin/sh <<'"
                + delimiter
                + "'\n"
                + script
                + "\n"
                + delimiter
                + "\n"
                + "printf '%s\\n' \"$?\" > \"$status_file\"\n"
                + "} | tee "
                + quotePosix(captureFile)
                + "\n"
                + "status=$(cat \"$status_file\")\n"
                + "exit \"$status\"\n";
    }

    private static String teePowerShellStdout(String script, String captureFile) {
        return "$pipelineEncoding = [Text.UTF8Encoding]::new($false)\r\n"
                + "$pipelineWriter = [IO.StreamWriter]::new("
                + quotePowerShell(captureFile)
                + ", $false, $pipelineEncoding)\r\n"
                + "$pipelineWriter.AutoFlush = $true\r\n"
                + "try {\r\n"
                + "& {\r\n"
                + script
                + "\r\n"
                + "} | ForEach-Object {\r\n"
                + "    $_ | Out-String -Stream -Width 192 | ForEach-Object {\r\n"
                + "        $pipelineWriter.WriteLine($_)\r\n"
                + "        Write-Output $_\r\n"
                + "    }\r\n"
                + "}\r\n"
                + "} finally {\r\n"
                + "    $pipelineWriter.Dispose()\r\n"
                + "}\r\n";
    }

    private static String heredocDelimiter(String script) {
        String delimiter;
        do {
            delimiter = "PIPELINE_EXEC_" + UUID.randomUUID().toString().replace("-", "");
        } while (script.contains(delimiter));
        return delimiter;
    }

    private static String quotePosix(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
