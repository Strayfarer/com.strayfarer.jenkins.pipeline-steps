package com.strayfarer.jenkins.pipelinesteps;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;

final class EnvironmentOverlayDurableTask extends DurableTask {

    private final DurableTask delegate;
    private final Map<String, String> overrides;

    EnvironmentOverlayDurableTask(DurableTask delegate, Map<String, String> overrides) {
        this.delegate = delegate;
        this.overrides = Map.copyOf(overrides);
    }

    @Override
    public void captureOutput() {
        delegate.captureOutput();
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
        EnvVars effective = new EnvVars(environment);
        effective.putAll(overrides);
        return delegate.launch(effective, workspace, launcher, listener);
    }
}
