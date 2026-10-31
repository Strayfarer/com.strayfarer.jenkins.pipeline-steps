package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;
import jenkins.MasterToSlaveFileCallable;
import org.jenkinsci.plugins.durabletask.BourneShellScript;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.durabletask.PowershellScript;

/** Tees command streams on the agent, independently of controller connectivity. */
final class OutputFileTaskFactory {

    private OutputFileTaskFactory() {}

    static DurableTask task(
            String script,
            FilePath workspace,
            boolean unix,
            String shell,
            boolean captureStdout,
            String encoding,
            String stdout,
            String stderr)
            throws IOException, InterruptedException {
        String charset = encoding == null || encoding.isEmpty() ? workspace.act(new DefaultCharset()) : encoding;
        Charset.forName(charset);
        String out = prepare(workspace, stdout);
        String err = prepare(workspace, stderr);
        boolean combined = out != null && err != null && workspace.act(new SameOutputFile(out, err));
        String capture = captureStdout
                ? WorkspaceTemporaryFiles.directory(workspace)
                        .child(".pipeline-exec-stdout-" + UUID.randomUUID())
                        .getRemote()
                : null;
        String failure = WorkspaceTemporaryFiles.directory(workspace)
                .child(".pipeline-exec-output-failure-" + UUID.randomUUID())
                .getRemote();
        DurableTask task;
        if (unix) {
            FilePath temporary =
                    WorkspaceTemporaryFiles.directory(workspace).child(".pipeline-exec-streams-" + UUID.randomUUID());
            temporary.mkdirs();
            task = new BourneShellScript(
                    unixScript(script, temporary.getRemote(), out, err, combined, capture, failure));
        } else {
            PowershellScript powershell =
                    new PowershellScript(windowsScript(script, shell, charset, out, err, combined, capture, failure));
            powershell.setPowershellBinary(shell);
            task = powershell;
        }
        return new CapturedOutputDurableTask(task, capture, unix ? null : Charset.forName(charset), failure);
    }

    private static String prepare(FilePath workspace, String path) throws IOException, InterruptedException {
        if (path == null) {
            return null;
        }
        FilePath file = workspace.child(path);
        requireNonNull(file.getParent(), "Output file has no parent directory: " + file)
                .mkdirs();
        return file.getRemote();
    }

    private static final class DefaultCharset extends MasterToSlaveFileCallable<String> {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public String invoke(File file, VirtualChannel channel) {
            return Charset.defaultCharset().name();
        }
    }

    private static final class SameOutputFile extends MasterToSlaveFileCallable<Boolean> {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String stdout;
        private final String stderr;

        private SameOutputFile(String stdout, String stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public Boolean invoke(File workspace, VirtualChannel channel) throws IOException {
            File out = new File(stdout);
            File err = new File(stderr);
            return out.getCanonicalFile().equals(err.getCanonicalFile())
                    || (out.exists() && err.exists() && Files.isSameFile(out.toPath(), err.toPath()));
        }
    }

    private static String unixScript(
            String script,
            String temporary,
            String stdout,
            String stderr,
            boolean combined,
            String capture,
            String failure) {
        StringBuilder result = new StringBuilder("#!/bin/sh\n");
        result.append("tmp=").append(posix(temporary)).append("\ntrap 'rm -rf -- \"$tmp\"' EXIT\n");
        result.append("output_failed() { : > ").append(posix(failure)).append("; status=125; }\n");
        String outPipe = temporary + "/stdout";
        String errPipe = combined ? temporary + "/combined" : temporary + "/stderr";
        String stdoutRedirect = "";
        String stderrRedirect = "";
        String waits = "";
        if (combined) {
            result.append("mkfifo ").append(posix(errPipe)).append(" || { output_failed; exit 125; }\n");
            result.append("tee ")
                    .append(posix(stdout))
                    .append(" < ")
                    .append(posix(errPipe))
                    .append(" &\nmerged_pid=$!\n");
            stderrRedirect = " 2> " + posix(errPipe);
            waits += "wait \"$merged_pid\" || output_failed\n";
            if (capture == null) {
                stdoutRedirect = " > " + posix(errPipe);
            }
        }
        if (capture != null || (stdout != null && !combined)) {
            result.append("mkfifo ").append(posix(outPipe)).append(" || { output_failed; exit 125; }\ntee");
            if (capture != null) {
                result.append(' ').append(posix(capture));
            }
            if (stdout != null && !combined) {
                result.append(' ').append(posix(stdout));
            }
            result.append(" < ").append(posix(outPipe));
            if (combined) {
                result.append(" > ").append(posix(errPipe));
            }
            result.append(" &\nout_pid=$!\n");
            stdoutRedirect = " > " + posix(outPipe);
            waits = "wait \"$out_pid\" || output_failed\n" + waits;
        }
        if (stderr != null && !combined) {
            result.append("mkfifo ")
                    .append(posix(errPipe))
                    .append(" || { output_failed; exit 125; }\ntee ")
                    .append(posix(stderr))
                    .append(" < ")
                    .append(posix(errPipe))
                    .append(" >&2 &\nerr_pid=$!\n");
            stderrRedirect = " 2> " + posix(errPipe);
            waits += "wait \"$err_pid\" || output_failed\n";
        }
        String delimiter = "PIPELINE_EXEC_" + UUID.randomUUID().toString().replace("-", "");
        result.append("/bin/sh <<'")
                .append(delimiter)
                .append('\'')
                .append(stdoutRedirect)
                .append(stderrRedirect)
                .append('\n')
                .append(script)
                .append('\n')
                .append(delimiter)
                .append("\nstatus=$?\n")
                .append(waits)
                .append("exit \"$status\"\n");
        return result.toString();
    }

    private static String windowsScript(
            String script,
            String shell,
            String encoding,
            String stdout,
            String stderr,
            boolean combined,
            String capture,
            String failure) {
        String command = "[Console]::OutputEncoding = [Text.Encoding]::GetEncoding("
                + powershell(encoding) + ")\r\n$OutputEncoding = [Console]::OutputEncoding\r\n"
                + script + "\r\nexit $LASTEXITCODE";
        String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        return "try {\nAdd-Type -TypeDefinition @'\n" + WINDOWS_RUNNER + "\n'@\n"
                + "exit [PipelineOutputFiles]::Run(" + powershell(shell) + ", " + powershell(encoded)
                + ", (Join-Path $PSScriptRoot 'pipeline-command.ps1'), "
                + powershell(encoding) + ", " + powershell(stdout) + ", " + powershell(stderr) + ", "
                + (combined ? "$true" : "$false") + ", " + powershell(capture) + ")\n"
                + "} catch { [IO.File]::WriteAllText(" + powershell(failure)
                + ", 'output streaming failed'); throw }\n";
    }

    private static String posix(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String powershell(String value) {
        return value == null ? "$null" : "'" + value.replace("'", "''") + "'";
    }

    // Both pumps serialize file and console writes through the same lock. A shared file therefore
    // contains exactly the command output sent to the console, including partial lines.
    private static final String WINDOWS_RUNNER = """
            using System;
            using System.Diagnostics;
            using System.IO;
            using System.Text;
            using System.Threading.Tasks;
            public static class PipelineOutputFiles {
                static StreamWriter Open(string path, Encoding encoding) {
                    if (String.IsNullOrEmpty(path)) return null;
                    var writer = new StreamWriter(new FileStream(path, FileMode.Create, FileAccess.Write,
                        FileShare.ReadWrite), encoding);
                    writer.AutoFlush = true;
                    return writer;
                }
                static void Pump(StreamReader reader, TextWriter console, StreamWriter file,
                        StreamWriter capture, object gate, Process process) {
                    try {
                        var buffer = new char[4096];
                        int count;
                        while ((count = reader.Read(buffer, 0, buffer.Length)) != 0) {
                            lock (gate) {
                                if (file != null) file.Write(buffer, 0, count);
                                if (capture != null) capture.Write(buffer, 0, count);
                                console.Write(buffer, 0, count);
                                console.Flush();
                            }
                        }
                    } catch {
                        try { process.Kill(); } catch (InvalidOperationException) { }
                        throw;
                    }
                }
                public static int Run(string shell, string command, string commandFile, string charset,
                        string stdout, string stderr, bool combined, string capture) {
                    var encoding = Encoding.GetEncoding(charset);
                    if (encoding.CodePage == 65001) encoding = new UTF8Encoding(false);
                    var gate = new object();
                    using (var outFile = Open(stdout, encoding))
                    using (var errFile = combined ? null : Open(stderr, encoding))
                    using (var captureFile = Open(capture, encoding))
                    using (var process = new Process()) {
                        File.WriteAllText(commandFile, Encoding.Unicode.GetString(Convert.FromBase64String(command)),
                            new UTF8Encoding(true));
                        process.StartInfo = new ProcessStartInfo(shell,
                            "-NoLogo -NoProfile -NonInteractive -OutputFormat Text -File \\\"" + commandFile + "\\\"");
                        process.StartInfo.UseShellExecute = false;
                        process.StartInfo.CreateNoWindow = true;
                        process.StartInfo.RedirectStandardOutput = true;
                        process.StartInfo.RedirectStandardError = true;
                        process.StartInfo.StandardOutputEncoding = encoding;
                        process.StartInfo.StandardErrorEncoding = encoding;
                        process.Start();
                        var output = Task.Run(() => Pump(process.StandardOutput, Console.Out,
                            outFile, captureFile, gate, process));
                        var error = Task.Run(() => Pump(process.StandardError,
                            combined ? Console.Out : Console.Error, combined ? outFile : errFile, null, gate, process));
                        process.WaitForExit();
                        Task.WaitAll(output, error);
                        return process.ExitCode;
                    }
                }
            }
            """;
}
