package com.strayfarer.jenkins.pipelinesteps;

import static java.util.Objects.requireNonNull;

import hudson.FilePath;
import hudson.slaves.WorkspaceList;
import java.io.IOException;

final class WorkspaceTemporaryFiles {

    private WorkspaceTemporaryFiles() {}

    static FilePath directory(FilePath workspace) {
        return requireNonNull(
                WorkspaceList.tempDir(workspace), "Workspace has no associated temporary directory: " + workspace);
    }

    static FilePath resolve(FilePath workspace, String path) {
        return new FilePath(workspace.getChannel(), path);
    }

    static void prepare(FilePath file) throws IOException, InterruptedException {
        requireNonNull(file.getParent(), "Temporary file has no parent directory: " + file)
                .mkdirs();
        file.delete();
    }
}
