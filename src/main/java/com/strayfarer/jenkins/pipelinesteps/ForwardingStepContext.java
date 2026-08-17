package com.strayfarer.jenkins.pipelinesteps;

import com.google.common.util.concurrent.ListenableFuture;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Result;
import java.io.IOException;
import java.io.Serial;
import java.util.Objects;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepContext;

abstract class ForwardingStepContext extends StepContext {

    @Serial
    private static final long serialVersionUID = 1L;

    final StepContext delegate;
    private final Object identity;

    ForwardingStepContext(StepContext delegate) {
        this(delegate, null);
    }

    ForwardingStepContext(StepContext delegate, Object identity) {
        this.delegate = delegate;
        this.identity = identity;
    }

    @Override
    public final <T> T get(Class<T> key) throws IOException, InterruptedException {
        return delegate.get(key);
    }

    @Override
    public void onFailure(@NonNull Throwable failure) {
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
                && delegate.equals(((ForwardingStepContext) object).delegate)
                && Objects.equals(identity, ((ForwardingStepContext) object).identity);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(getClass(), delegate, identity);
    }
}
