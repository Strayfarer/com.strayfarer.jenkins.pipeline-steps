package com.strayfarer.jenkins.pipelinesteps;

import hudson.model.InvisibleAction;
import java.io.IOException;
import org.jenkinsci.plugins.workflow.actions.StageAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/** Marks an {@code everyNode} body as a Jenkins stage named after its concrete node. */
@SuppressWarnings("deprecation")
final class EveryNodeStageAction extends InvisibleAction implements StageAction {

    private final String stageName;

    EveryNodeStageAction(String stageName) {
        this.stageName = stageName;
    }

    static Throwable attach(StepContext context, String stageName) {
        try {
            FlowNode node = context.get(FlowNode.class);
            if (node == null) {
                return new IOException("everyNode body has no Pipeline flow node");
            }
            node.addAction(new EveryNodeStageAction(stageName));
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return failure;
        } catch (IOException failure) {
            return failure;
        }
    }

    @Override
    public String getStageName() {
        return stageName;
    }
}
