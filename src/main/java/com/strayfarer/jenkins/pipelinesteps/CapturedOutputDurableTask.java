package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;

/** Keeps the delegate in streaming mode while supplying separately captured stdout. */
final class CapturedOutputDurableTask extends DurableTask {

    private final DurableTask delegate;
    private final String captureFile;

    CapturedOutputDurableTask(DurableTask delegate, String captureFile) {
        this.delegate = delegate;
        this.captureFile = captureFile;
    }

    @Override
    public void captureOutput() {
        // The command wrapper captures stdout while the delegate continues streaming it.
    }

    @Override
    public void charset(Charset charset) {
        delegate.charset(charset);
    }

    @Override
    public void defaultCharset() {
        delegate.defaultCharset();
    }

    @Override
    public Controller launch(EnvVars environment, FilePath workspace, Launcher launcher, TaskListener listener)
            throws IOException, InterruptedException {
        workspace.child(captureFile).delete();
        return new CapturedOutputController(delegate.launch(environment, workspace, launcher, listener), captureFile);
    }

    private static final class CapturedOutputController extends Controller {

        private static final long serialVersionUID = 1L;

        private final Controller delegate;
        private final String captureFile;

        private CapturedOutputController(Controller delegate, String captureFile) {
            this.delegate = delegate;
            this.captureFile = captureFile;
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
        public byte[] getOutput(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            FilePath output = workspace.child(captureFile);
            if (!output.exists()) {
                return new byte[0];
            }
            try (InputStream stream = output.read()) {
                return stream.readAllBytes();
            }
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
                workspace.child(captureFile).delete();
            }
        }

        @Override
        public String getDiagnostics(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            return delegate.getDiagnostics(workspace, launcher);
        }
    }
}
