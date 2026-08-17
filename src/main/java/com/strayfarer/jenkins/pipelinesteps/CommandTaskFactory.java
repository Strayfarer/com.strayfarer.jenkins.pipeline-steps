package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
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

    static DurableTask nativeTask(String script, Launcher launcher, EnvVars environment, boolean teeStdout)
            throws IOException, InterruptedException {
        if (launcher.isUnix()) {
            return task(script, true, null, teeStdout);
        }
        boolean pwshAvailable;
        try {
            pwshAvailable = commandExists("pwsh", launcher, environment);
        } catch (IOException exception) {
            pwshAvailable = false;
        }
        String shell = pwshAvailable ? "pwsh" : "powershell";
        return task(script, false, shell, teeStdout);
    }

    static DurableTask task(String script, boolean unix, String windowsShell, boolean teeStdout) {
        String captureFile = ".pipeline-exec-stdout-" + UUID.randomUUID();
        DurableTask task;
        if (unix) {
            task = new BourneShellScript(teeStdout ? teeUnixStdout(script, captureFile) : "#!/bin/sh\n" + script);
        } else {
            PowershellScript powershellScript =
                    new PowershellScript(teeStdout ? teePowerShellStdout(script, captureFile) : script);
            powershellScript.setPowershellBinary(windowsShell);
            task = powershellScript;
        }
        return teeStdout
                ? new CapturedOutputDurableTask(task, captureFile, unix ? null : StandardCharsets.UTF_8)
                : task;
    }

    static String selectWindowsShell(Predicate<String> available) {
        return available.test("pwsh") ? "pwsh" : "powershell";
    }

    private static boolean commandExists(String command, Launcher launcher, EnvVars environment)
            throws IOException, InterruptedException {
        return launcher.launch()
                        .cmds(command, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                        .envs(environment)
                        .quiet(true)
                        .stdout(OutputStream.nullOutputStream())
                        .stderr(OutputStream.nullOutputStream())
                        .join()
                == 0;
    }

    private static String teeUnixStdout(String script, String captureFile) {
        String delimiter = heredocDelimiter(script);
        return "#!/bin/sh\n"
                + "status_file=\"${WORKSPACE_TMP:-.}/.pipeline-exec-status-$$\"\n"
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
                + "} | tee '"
                + captureFile
                + "'\n"
                + "status=$(cat \"$status_file\")\n"
                + "exit \"$status\"\n";
    }

    private static String teePowerShellStdout(String script, String captureFile) {
        return "$pipelineEncoding = [Text.UTF8Encoding]::new($false)\r\n"
                + "$pipelineWriter = [IO.StreamWriter]::new([IO.Path]::GetFullPath('"
                + captureFile
                + "'), $false, $pipelineEncoding)\r\n"
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
}
