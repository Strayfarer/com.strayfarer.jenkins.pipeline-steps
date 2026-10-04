package com.strayfarer.jenkins.pipelinesteps;

import hudson.AbortException;
import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

record DockerContext(String container, Map<String, String> label, List<String> environment) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    DockerContext(String container, List<String> environment) {
        this(container, Map.of(), environment);
    }

    DockerContext {
        label = normalizeLabel(label);
        if ((container == null || container.isBlank()) == label.isEmpty()) {
            throw new IllegalArgumentException("Exactly one of container or label is required");
        }
        environment = normalizeEnvironment(environment);
    }

    static Map<String, String> normalizeLabel(Map<?, ?> label) {
        if (label == null || label.isEmpty()) {
            return Map.of();
        }
        if (label.size() != 1) {
            throw new IllegalArgumentException("label must contain exactly one key/value pair");
        }
        Map.Entry<?, ?> entry = label.entrySet().iterator().next();
        if (!(entry.getKey() instanceof String key) || key.isBlank() || key.contains("=")) {
            throw new IllegalArgumentException("label key must be a nonempty string without '='");
        }
        if (!(entry.getValue() instanceof String value)) {
            throw new IllegalArgumentException("label value must be a string");
        }
        return Map.of(key, value);
    }

    String labelFilter() {
        Map.Entry<String, String> entry = label.entrySet().iterator().next();
        return entry.getKey() + "=" + entry.getValue();
    }

    static List<String> normalizeEnvironment(List<String> environment) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (environment != null) {
            for (String entry : environment) {
                String name = entry == null ? "" : entry.trim();
                if (name.isEmpty()) {
                    continue;
                }
                if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid environment variable name: " + name);
                }
                normalized.add(name);
            }
        }
        return List.copyOf(normalized);
    }

    static String osFromInspection(String container, String output) throws AbortException {
        String[] lines = output.strip().split("\\R", 3);
        if (lines.length == 0 || !"0".equals(lines[0])) {
            throw new AbortException("Docker container '" + container + "' does not exist or cannot be inspected");
        }
        if (lines.length < 2) {
            throw new AbortException("Docker returned incomplete inspection data for '" + container + "'");
        }
        String[] fields = lines[1].trim().split("\\s+", -1);
        if (fields.length != 2) {
            throw new AbortException("Docker returned invalid inspection data for '" + container + "'");
        }
        if (!Boolean.parseBoolean(fields[0])) {
            throw new AbortException("Docker container '" + container + "' is not running");
        }
        if (!"linux".equals(fields[1]) && !"windows".equals(fields[1])) {
            throw new AbortException("Docker container '" + container + "' uses unsupported OS '" + fields[1] + "'");
        }
        return fields[1];
    }
}
