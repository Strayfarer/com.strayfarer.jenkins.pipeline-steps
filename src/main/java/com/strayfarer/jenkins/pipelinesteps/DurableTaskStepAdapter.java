package com.strayfarer.jenkins.pipelinesteps;

import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep;

final class DurableTaskStepAdapter extends DurableTaskStep {

    private final DurableTask task;

    DurableTaskStepAdapter(DurableTask task) {
        this.task = task;
    }

    @Override
    protected DurableTask task() {
        return task;
    }
}
