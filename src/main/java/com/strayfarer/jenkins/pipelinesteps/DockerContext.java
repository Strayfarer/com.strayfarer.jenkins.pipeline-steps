package com.strayfarer.jenkins.pipelinesteps;

import hudson.AbortException;
import java.io.Serializable;
import java.util.List;

record DockerContext(String container, String id, String os, List<String> environment) implements Serializable {

    private static final long serialVersionUID = 1L;

    DockerContext {
        environment = List.copyOf(environment);
    }

    static DockerContext fromInspection(String container, List<String> environment, String output)
            throws AbortException {
        String[] lines = output.strip().split("\\R", 3);
        if (lines.length == 0 || !"0".equals(lines[0])) {
            throw new AbortException("Docker container '" + container + "' does not exist or cannot be inspected");
        }
        if (lines.length < 2) {
            throw new AbortException("Docker returned incomplete inspection data for '" + container + "'");
        }
        String[] fields = lines[1].trim().split("\\s+", -1);
        if (fields.length != 3 || fields[0].isEmpty()) {
            throw new AbortException("Docker returned invalid inspection data for '" + container + "'");
        }
        if (!Boolean.parseBoolean(fields[1])) {
            throw new AbortException("Docker container '" + container + "' is not running");
        }
        if (!"linux".equals(fields[2]) && !"windows".equals(fields[2])) {
            throw new AbortException("Docker container '" + container + "' uses unsupported OS '" + fields[2] + "'");
        }
        return new DockerContext(container, fields[0], fields[2], environment);
    }
}
