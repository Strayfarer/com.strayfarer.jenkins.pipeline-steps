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
            throws IOException, InterruptedException {
        return nativeTask(script, workspace, launcher, environment, teeStdout, "UTF-8", null, null);
    }

    static DurableTask nativeTask(
            String script,
            FilePath workspace,
            Launcher launcher,
            EnvVars environment,
            boolean teeStdout,
            String encoding,
            String stdout,
            String stderr)
            throws IOException, InterruptedException {
        if (launcher.isUnix()) {
            if (teeStdout || stdout != null || stderr != null) {
                return OutputFileTaskFactory.task(script, workspace, true, null, teeStdout, encoding, stdout, stderr);
            }
            return task(script, workspace, true, null, teeStdout);
        }
        boolean pwshAvailable;
        try {
            pwshAvailable = pwshExists(launcher, environment);
        } catch (IOException exception) {
            pwshAvailable = false;
        }
        String shell = pwshAvailable ? "pwsh" : "powershell";
        if (stdout != null || stderr != null) {
            return OutputFileTaskFactory.task(script, workspace, false, shell, teeStdout, encoding, stdout, stderr);
        }
        return task(script, workspace, false, shell, teeStdout);
    }

    static DurableTask task(String script, FilePath workspace, boolean unix, String windowsShell, boolean teeStdout)
            throws IOException, InterruptedException {
        if (unix && teeStdout) {
            return OutputFileTaskFactory.task(script, workspace, true, null, true, "UTF-8", null, null);
        }
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
        PowershellScript powershellScript = new PowershellScript(teePowerShellStdout(script, captureFile));
        powershellScript.setPowershellBinary(windowsShell);
        return new CapturedOutputDurableTask(powershellScript, captureFile, StandardCharsets.UTF_8);
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

    private static String quotePowerShell(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
