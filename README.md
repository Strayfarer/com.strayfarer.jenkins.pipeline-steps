# Strayfarer Pipeline Steps

Generic, restart-safe Jenkins Pipeline steps for running commands on agents or
inside existing Docker sidecars, and for running a Pipeline body on every node
matching a label expression.

> [!IMPORTANT]
> This repository currently contains the plugin scaffold and API contract. The
> steps described below are not implemented yet.

## Scope

The plugin provides four Pipeline steps:

- `exec`, `execStatus`, and `execStdout` run a command using the native shell of
  the current agent or active Docker sidecar.
- `insideDockerContainer` lexically routes nested `exec*` calls into a named,
  already-running Docker container.
- `everyNode` runs a Pipeline body once on every online node matching a Jenkins
  label expression.

The plugin does not own application-specific build orchestration. In
particular, Unity project and package Pipelines remain in their Shared Library.

## Command execution

All command steps require a Jenkins node and workspace. Linux commands run
through `/bin/sh`; Windows commands run through PowerShell 7 (`pwsh`). UTF-8 is
the default encoding.

### `exec`

```groovy
exec 'dotnet test'

exec script: 'dotnet test', echoScript: true
```

`exec` streams stdout and stderr. It succeeds with no meaningful return value
when the command exits with status zero and fails the Pipeline step when the
command exits nonzero.

### `execStatus`

```groovy
int status = execStatus 'git diff --quiet'
```

`execStatus` streams stdout and stderr and returns the numeric exit status. A
nonzero status does not by itself fail the Pipeline step.

### `execStdout`

```groovy
String version = execStdout 'git describe --tags --always'
```

`execStdout` streams stdout and stderr while the command runs, then returns a
trimmed copy of stdout. Stderr is never included in the return value. A nonzero
exit status fails the Pipeline step.

### Common options

The single-string form is shorthand for the `script` option. The map form
supports these common options:

| Option | Type | Default | Meaning |
| --- | --- | --- | --- |
| `script` | `String` | required | Command text passed to the selected shell. |
| `echoScript` | `boolean` | `false` | Explicitly print the command before execution. |
| `encoding` | `String` | `UTF-8` | Encoding used for command output. |

Command execution must be durable: agent disconnection or controller restart
must not discard a running process, its output, or its final status. Aborting
the Pipeline must terminate the selected host or container process and preserve
Jenkins interruption semantics.

## Docker sidecars

`insideDockerContainer` selects an existing Docker container for nested
`exec*` calls:

```groovy
insideDockerContainer('build-sidecar') {
    exec 'dotnet test'
    String version = execStdout 'dotnet --version'
}
```

The extended form forwards an explicit environment-variable allowlist by name:

```groovy
insideDockerContainer(
    container: 'build-sidecar',
    environment: ['NUGET_USERNAME', 'NUGET_PASSWORD']
) {
    exec 'dotnet nuget push package.nupkg'
}
```

Values are resolved from the Pipeline environment at execution time. Values
must not be placed in Docker command-line arguments or logs. Environment names
must match `[A-Za-z_][A-Za-z0-9_]*`; empty entries are ignored and duplicates
are forwarded once.

At scope entry, the plugin inspects the container once and rejects a missing,
stopped, or unsupported container. Linux and Windows containers are supported.
Docker inspection and `docker exec` always run on the Jenkins agent, even when
container scopes are nested.

Nested scopes are lexical. The innermost scope wins, and the previous context
is restored after success, failure, or interruption:

```groovy
insideDockerContainer('outer') {
    exec 'runs in outer'

    insideDockerContainer('inner') {
        exec 'runs in inner'
    }

    exec 'runs in outer again'
}
```

The active scope contributes the following metadata to its body:

- `PIPELINE_DOCKER_CONTAINER_NAME`
- `PIPELINE_DOCKER_CONTAINER_ID`
- `PIPELINE_DOCKER_CONTAINER_OS`

These values are scoped implementation metadata, not a mechanism for enabling
container execution. Setting them manually must not affect `exec*` routing.

Commands run with the current Jenkins `pwd()` as their container working
directory. The workspace, `WORKSPACE_TMP`, and nested temporary directories
must therefore be mounted into the container at identical absolute paths. The
agent must have Docker CLI access to the daemon hosting the sidecar.

Linux containers must provide `/bin/sh`, `setsid`, and `pkill`. Windows
containers must provide PowerShell 7 as `pwsh` and `taskkill.exe`.

## Every matching node

`everyNode` accepts the same kind of label expression as `node` and executes its
body once on every matching online node:

```groovy
everyNode('unity && linux') {
    echo "Running on ${env.NODE_NAME}"
}
```

Execution is sequential by default. Parallel execution is explicit:

```groovy
everyNode(label: 'unity', parallel: true) {
    echo "Running on ${env.NODE_NAME}"
}
```

The selection contract is:

1. Parse the Jenkins label expression.
2. Snapshot the concrete online nodes that match when the step starts.
3. Allocate each selected node through the normal Jenkins queue.
4. Invoke the body inside that node's executor and workspace.

The normal `env.NODE_NAME` identifies the selected node; the body receives no
positional arguments. The built-in node is eligible only when it matches the
label and has executors. No matches fail the step with a clear error. If a node
goes offline after selection, normal exact-node queue behavior applies and the
branch waits for that node to return.

Parallel branches are named after their concrete nodes. A failure in any branch
fails `everyNode`; interruption is propagated without conversion to an
ordinary failure.

## Shared Library migration

The new names let this plugin coexist with the existing Jenkins Shared Library:

| Shared Library step | Plugin step |
| --- | --- |
| `callShell` | `exec` |
| `callShellStatus` | `execStatus` |
| `callShellStdout` | `execStdout` |
| `withUnity` execution scope | `insideDockerContainer` |
| `executeOnAll` | `everyNode` |

The Unity-specific wrapper can remain in the Shared Library:

```groovy
def call(Closure body) {
    insideDockerContainer(
        container: env.JENKINS_UNITY_CONTAINER,
        environment: (env.JENKINS_UNITY_ENV ?: '').tokenize(':')
    ) {
        body()
    }
}
```

Unity initialization can distinguish a replaced sidecar through
`PIPELINE_DOCKER_CONTAINER_ID`.

## Development

The scaffold uses Maven 3.9.6 or newer, Java 21, and the Jenkins plugin parent
POM. The provisional minimum Jenkins version is 2.555.3 and should be checked
against the target controllers before the first release.

```shell
./mvnw verify
./mvnw hpi:run
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

Planned verification includes Jenkins test-harness Pipeline tests, restart and
abort tests, concurrent and nested container scopes, Linux and Windows agents,
Linux and Windows sidecars, sequential and parallel node execution, and
credential masking.

## License

[MIT](LICENSE)
