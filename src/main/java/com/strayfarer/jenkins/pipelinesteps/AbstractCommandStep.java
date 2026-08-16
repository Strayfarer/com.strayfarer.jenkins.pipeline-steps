package com.strayfarer.jenkins.pipelinesteps;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Result;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;
import org.kohsuke.stapler.DataBoundSetter;

abstract class AbstractCommandStep extends Step {

    enum ResultMode {
        NONE,
        STATUS,
        STDOUT
    }

    private final String script;
    private boolean echoScript;
    private String encoding = "UTF-8";

    AbstractCommandStep(String script) {
        if (script == null) {
            throw new IllegalArgumentException("script is required");
        }
        this.script = script;
    }

    public final String getScript() {
        return script;
    }

    public final boolean isEchoScript() {
        return echoScript;
    }

    @DataBoundSetter
    public final void setEchoScript(boolean echoScript) {
        this.echoScript = echoScript;
    }

    public final String getEncoding() {
        return encoding;
    }

    @DataBoundSetter
    public final void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    abstract ResultMode resultMode();

    @Override
    public final StepExecution start(StepContext context) throws Exception {
        if (echoScript) {
            context.get(TaskListener.class).getLogger().println(script);
        }

        ResultMode mode = resultMode();
        DurableTask task = CommandTaskFactory.nativeTask(
                script, context.get(Launcher.class), context.get(EnvVars.class), mode == ResultMode.STDOUT);
        ConfiguredDurableTaskStep taskStep = new ConfiguredDurableTaskStep(task);
        taskStep.setEncoding(encoding);
        taskStep.setReturnStatus(mode == ResultMode.STATUS);
        taskStep.setReturnStdout(mode == ResultMode.STDOUT);
        StepContext resultContext = mode == ResultMode.STDOUT ? new TrimmedOutputContext(context) : context;
        return taskStep.start(resultContext);
    }

    abstract static class Descriptor extends StepDescriptor {

        @Override
        public final Set<? extends Class<?>> getRequiredContext() {
            return Set.of(FilePath.class, EnvVars.class, Launcher.class, TaskListener.class);
        }

        @Override
        public final String argumentsToString(Map<String, Object> namedArgs) {
            Object script = namedArgs.get("script");
            return script instanceof String ? (String) script : null;
        }
    }

    private static final class ConfiguredDurableTaskStep extends DurableTaskStep {

        private final DurableTask task;

        private ConfiguredDurableTaskStep(DurableTask task) {
            this.task = task;
        }

        @Override
        protected DurableTask task() {
            return task;
        }
    }

    private static final class TrimmedOutputContext extends StepContext {

        private static final long serialVersionUID = 1L;

        private final StepContext delegate;

        private TrimmedOutputContext(StepContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T get(Class<T> key) throws IOException, InterruptedException {
            return delegate.get(key);
        }

        @Override
        public void onSuccess(Object result) {
            delegate.onSuccess(result instanceof String ? ((String) result).trim() : result);
        }

        @Override
        public void onFailure(Throwable failure) {
            delegate.onFailure(failure);
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public ListenableFuture<Void> saveState() {
            return delegate.saveState();
        }

        @Override
        public void setResult(Result result) {
            delegate.setResult(result);
        }

        @Override
        public BodyInvoker newBodyInvoker() throws IllegalStateException {
            return delegate.newBodyInvoker();
        }

        @Override
        public boolean hasBody() {
            return delegate.hasBody();
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof TrimmedOutputContext && delegate.equals(((TrimmedOutputContext) object).delegate);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }
    }
}
