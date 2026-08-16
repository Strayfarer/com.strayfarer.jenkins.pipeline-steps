package com.strayfarer.jenkins.pipelinesteps;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.model.Result;
import java.io.IOException;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepContext;

abstract class ForwardingStepContext extends StepContext {

    private static final long serialVersionUID = 1L;

    final StepContext delegate;

    ForwardingStepContext(StepContext delegate) {
        this.delegate = delegate;
    }

    @Override
    public final <T> T get(Class<T> key) throws IOException, InterruptedException {
        return delegate.get(key);
    }

    @Override
    public void onFailure(Throwable failure) {
        delegate.onFailure(failure);
    }

    @Override
    public final boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public final ListenableFuture<Void> saveState() {
        return delegate.saveState();
    }

    @Override
    public final void setResult(Result result) {
        delegate.setResult(result);
    }

    @Override
    public final BodyInvoker newBodyInvoker() throws IllegalStateException {
        return delegate.newBodyInvoker();
    }

    @Override
    public final boolean hasBody() {
        return delegate.hasBody();
    }

    @Override
    public final boolean equals(Object object) {
        return object != null
                && object.getClass() == getClass()
                && delegate.equals(((ForwardingStepContext) object).delegate);
    }

    @Override
    public final int hashCode() {
        return 31 * getClass().hashCode() + delegate.hashCode();
    }
}
