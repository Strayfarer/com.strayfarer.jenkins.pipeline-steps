# AGENTS.md

Shared instructions for coding agents. Project-specific information is kept in [README.md](README.md), read it before non-trivial changes.

## Jenkins

### Repository conventions

- Follow the existing source layout and build system. Do not introduce or
  replace a build framework for local convenience.
- Put stable user-facing behavior and examples in `README.md`.
- Keep public Pipeline contracts backward-compatible unless a change explicitly
  permits a breaking release. Preserve each helper's streamed-output,
  captured-stdout, or numeric-exit-status contract.

### Groovy style

Match the touched file. Do not normalize unrelated indentation or formatting.
Opening braces stay on declaration or control-flow lines. Prefer single-quoted
literals and double-quoted interpolation. Omit semicolons in new code. Use
camelCase for locals and methods, PascalCase for classes, and uppercase snake
case for public Pipeline configuration keys and environment variables.

### Jenkins CPS safety

- Do not retain non-serializable Jenkins, Hudson, iterator, matcher, stream, or
  platform objects across Pipeline step calls.
- Treat `node`, `stage`, `dir`, `sh`, `powershell`, `withEnv`,
  `withCredentials`, `stash`, and similar calls as suspension points.
- Do not store build-local state in ordinary static fields.
- Make scoped behavior lexical, nestable, concurrency-safe, and exception-safe.

### Errors, interruptions, and results

Preserve `FlowInterruptedException`: set build result when appropriate, then
rethrow it before broad `Throwable` catches. Do not convert aborts or timeouts
to ordinary failures.

### Shells, paths, and external processes

Support Windows PowerShell and Linux POSIX shells where claimed. Treat quoting
as multiple layers: Groovy, Jenkins, host shell, optional transport, target
shell. Use Jenkins `pwd()`, `WORKSPACE`, and `WORKSPACE_TMP`; do not compute
agent paths with controller-local `File` APIs. Never assume active Docker
context.

### Credentials and sensitive data

Bind secrets with Jenkins credential steps and minimize scope. Never print
tokens, passwords, secrets, credential files, or full environments. Preserve
masking and never archive, stash, or publish credential files.

### Jenkins environment and validation

Prefer configured Jenkins integration over scraping HTML. Read-only build
inspection is allowed when relevant. Triggering, replaying, stopping, or
mutating builds requires explicit authorization. Local or standalone execution
cannot faithfully reproduce every Jenkins CPS, durability, agent, and plugin
integration behavior. Whenever integration tests are run, watch the complete
console log; scheduling a build alone does not establish success.

The durable test servers are `Mörkö`, a Linux server that also hosts the
Jenkins container; `Garl`, a Linux server with a GPU; and `Dende`, a Windows
server with a GPU. Other Jenkins agents are temporary build helpers: do not
mention them by name in `.jenkins/Jenkinsfile.groovy`. Target helpers only by
label, and require an available helper matching a tested label to pass the same
integration tests as the named servers.

## Jenkins Plugin Development

### Implementation and tests

- Follow the existing Maven and Jenkins plugin structure. Keep Jenkins agent
  operations on the agent and use the established durable Pipeline mechanisms.
- Run the local Maven verification described in `README.md` before live
  integration testing.
- Keep this plugin's integration tests in `.jenkins/Jenkinsfile.groovy`. The
  Jenkinsfile must exercise all of the plugin's public Pipeline steps and should
  retain assertions for previously discovered regressions.
- The Jenkins controller runs that file through a job under
  `https://ci.slothsoft.net/job/jenkins/` whose name matches the Jenkins plugin
  ID.

### Deployment and release

- The deployment target is container `jenkins` on Docker context `groke`.
  Always pass `--context groke`; do not rely on the active Docker context. This
  installation uses `JENKINS_HOME=/jenkins/home`.
- Restart Jenkins only when it has no running or queued jobs. After restarting,
  wait for the container log to report `Jenkins is fully up and running`,
  verify the plugin version in the installed JPI manifest, and check startup
  logs for plugin-load failures.

When release operations are authorized, the complete release cycle is:

1. Implement the features and update the integration tests in `.jenkins/Jenkinsfile.groovy` as needed.
2. Run the local test suite.
3. Commit, then build an unpublished candidate HPI with version `<next-version>-rc.<commit-hash>`.
4. Install the candidate HPI into `groke` container `jenkins`.
5. Restart Jenkins after confirming it has no running or queued jobs, verify
   the candidate version in the installed JPI manifest, and check startup logs
   for plugin-load failures.
6. Run this plugin's job in `https://ci.slothsoft.net/job/jenkins/` and watch its complete console log.
7. If the candidate integration test fails, fix the issue, then repeat from step 2.
8. After the candidate passes, amend CHANGELOG.md, commit and push the changes, then watch the GitHub CI checks.
9. If the GitHub CI checks fail, fix the issue, then repeat from step 2.
10. After GitHub CI passes, tag the final version and publish its release artifacts.
11. Download and install the final HPI into `groke` container `jenkins`.
12. Restart Jenkins after confirming it has no running or queued jobs, verify
   the final version in the installed JPI manifest, and check startup logs for plugin-load failures.
13. Run this plugin's job in `https://ci.slothsoft.net/job/jenkins/` and watch its complete console log.
14. If any post-push check or final integration test fails, fix the issue and
    repeat the full cycle from step 1 with a new patch version.

## General

### Meta commands

These short messages have special handling when they appear alone in a user
message:

- `ping`: Reply with `pong`.
- `.`: Reply with `.`.
- `?`: Continue the previous response or task after an interruption.
- `ticket <URI>`: Read the linked ticket and all comments through the available
  integration. Inspect the project, reproduce the current behavior, and run
  relevant checks as needed. Then explain the request, project context,
  reproducibility, risks, and a proposed implementation plan. Do not edit
  files, change remote state, commit, or push until the user approves the
  approach.
- `can you <x>?` is a question about your knowledge, capabilities or permissions. It is not an instruction to perform `x`.

### Compatibility

Follow semantic versioning. Preserve backward compatibility for public APIs
unless the task explicitly permits a breaking change.

### Project conventions

`.editorconfig` is authoritative. Never edit `.editorconfig` unless expressly instructed by the user.

### Git

Git mutations are forbidden by default. Agents may use read-only inspection
commands such as `git status`, `git log`, `git diff`, `git show`, `git blame`,
and `git branch --list` without additional permission.

An agent may perform Git mutations only after the user explicitly opts in.
Permission is limited to the operations and task the user authorized; do not
treat prior authorization as standing permission for later mutations.

When Git mutations are authorized:

- The user is responsible for choosing the branch. Verify the current branch
  and working-tree status before editing and again before creating commits.
- Treat all unknown local changes as user work. Do not overwrite, stage,
  commit, restore, or otherwise alter them.
- Keep commits small and cohesive.
- Format agent-authored commits according to Conventional Commits 1.0.0:
  `<type>[optional scope]: <description>`.
- When working from a ticket, include the ticket key and URL in the commit
  footer.
- Before committing, read the configured Git author name and email. Keep the
  configured email, append the agent name once, in brackets to the configured author name (e.g. `Daniel Schulz (Codex)`),
  and pass that identity explicitly with `git commit --author`. Do not modify
  repository or global Git configuration.
- Do not force-push, amend, rebase, reset, or discard changes unless the user
  explicitly requests that specific operation.
