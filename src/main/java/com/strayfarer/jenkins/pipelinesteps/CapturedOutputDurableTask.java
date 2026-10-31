package com.strayfarer.jenkins.pipelinesteps;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serial;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import jenkins.MasterToSlaveFileCallable;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;

/** Keeps the delegate in streaming mode while supplying separately captured stdout. */
final class CapturedOutputDurableTask extends DurableTask {

    private final DurableTask delegate;
    private final String captureFile;
    private final String fixedCaptureCharset;
    private final String outputFailureFile;
    private String captureCharset = StandardCharsets.UTF_8.name();

    String outputFailureFile() {
        return outputFailureFile;
    }

    CapturedOutputDurableTask(DurableTask delegate, String captureFile, Charset fixedCaptureCharset) {
        this(delegate, captureFile, fixedCaptureCharset, null);
    }

    CapturedOutputDurableTask(
            DurableTask delegate, String captureFile, Charset fixedCaptureCharset, String outputFailureFile) {
        this.delegate = delegate;
        this.captureFile = captureFile;
        this.fixedCaptureCharset = fixedCaptureCharset == null ? null : fixedCaptureCharset.name();
        this.outputFailureFile = outputFailureFile;
    }

    @Override
    public void captureOutput() {
        // The command wrapper captures stdout while the delegate continues streaming it.
        if (captureFile == null) {
            delegate.captureOutput();
        }
    }

    @Override
    public void charset(@NonNull Charset charset) {
        delegate.charset(charset);
        if (fixedCaptureCharset == null) {
            captureCharset = charset.name();
        }
    }

    @Override
    public void defaultCharset() {
        delegate.defaultCharset();
        if (fixedCaptureCharset == null) {
            captureCharset = null;
        }
    }

    @Override
    public Controller launch(EnvVars environment, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        if (captureFile != null) {
            WorkspaceTemporaryFiles.prepare(WorkspaceTemporaryFiles.resolve(workspace, captureFile));
        }
        if (outputFailureFile != null) {
            WorkspaceTemporaryFiles.prepare(WorkspaceTemporaryFiles.resolve(workspace, outputFailureFile));
        }
        String outputCharset = fixedCaptureCharset == null ? captureCharset : fixedCaptureCharset;
        return new CapturedOutputController(
                delegate.launch(environment, workspace, launcher, listener),
                captureFile,
                outputCharset,
                outputFailureFile);
    }

    private static final class CapturedOutputController extends Controller {

        @Serial
        private static final long serialVersionUID = 1L;

        private final Controller delegate;
        private final String captureFile;
        private final String captureCharset;
        private final String outputFailureFile;

        private CapturedOutputController(
                Controller delegate, String captureFile, String captureCharset, String outputFailureFile) {
            this.delegate = delegate;
            this.captureFile = captureFile;
            this.captureCharset = captureCharset;
            this.outputFailureFile = outputFailureFile;
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
            if (captureFile == null) {
                return delegate.getOutput(workspace, launcher);
            }
            FilePath output = WorkspaceTemporaryFiles.resolve(workspace, captureFile);
            if (!output.exists()) {
                return new byte[0];
            }
            return output.act(new ReadCapturedOutput(captureCharset));
        }

        @Override
        public void stop(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            delegate.stop(workspace, launcher);
        }

        @Override
        public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            try {
                delegate.cleanup(workspace);
            } finally {
                if (captureFile != null) {
                    WorkspaceTemporaryFiles.resolve(workspace, captureFile).delete();
                }
                if (outputFailureFile != null) {
                    WorkspaceTemporaryFiles.resolve(workspace, outputFailureFile)
                            .delete();
                }
            }
        }

        @Override
        public String getDiagnostics(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            return delegate.getDiagnostics(workspace, launcher);
        }
    }

    private static final class ReadCapturedOutput extends MasterToSlaveFileCallable<byte[]> {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String charset;

        private ReadCapturedOutput(String charset) {
            this.charset = charset;
        }

        @Override
        public byte[] invoke(File file, VirtualChannel channel) throws IOException {
            Charset source = charset == null ? Charset.defaultCharset() : Charset.forName(charset);
            return Files.readString(file.toPath(), source).getBytes(StandardCharsets.UTF_8);
        }
    }
}
