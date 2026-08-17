# AGENTS.md

Shared instructions for coding agents. Project-specific information is kept in [README.md](README.md), read it before non-trivial changes.

## Jenkins

### Repository conventions

- Follow existing source layout and build system. Do not introduce Gradle,
  Maven, or another framework for local convenience.
- In Jenkins Shared Libraries, treat `vars/*.groovy` as global steps. Filename
  defines public step name; overloaded `call` methods define invocation forms.
- Keep public APIs small. Prefer a `Map` for programmatic Pipeline configuration
  and delegated `Closure` where a DSL improves readability.
- Keep reusable classes outside global-step scripts when they do not need
  Pipeline script binding. Static state is controller-JVM-wide across builds.
- Put stable user-facing behavior and examples in `README.md`.

### Groovy style

Match touched file. Do not normalize unrelated indentation or formatting.
Opening braces stay on declaration or control-flow lines. Prefer single-quoted
literals and double-quoted interpolation. Omit semicolons in new code. Use
camelCase for locals and methods, PascalCase for classes, and uppercase snake
case for public Pipeline configuration keys and environment variables.

### Jenkins CPS safety

- Do not retain non-serializable Jenkins, Hudson, iterator, matcher, stream, or
  platform objects across Pipeline step calls.
- Use `@NonCPS` only for pure computation invoking no Pipeline steps.
- Treat `node`, `stage`, `dir`, `sh`, `powershell`, `withEnv`,
  `withCredentials`, `stash`, and similar calls as suspension points.
- Do not store build-local state in ordinary static fields.
- Make scoped behavior lexical, nestable, concurrency-safe, and exception-safe.
- IDE unresolved-symbol warnings alone do not prove a dynamic global-step error.

### Errors, interruptions, and results

Preserve `FlowInterruptedException`: set build result when appropriate, then
rethrow it before broad `Throwable` catches. Do not convert aborts or timeouts
to ordinary failures. Preserve each helper's streamed-output, captured-stdout,
or numeric-exit-status contract.

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

### Jenkins validation

Prefer configured Jenkins integration over scraping HTML. Read-only build
inspection is allowed when relevant. Triggering, replaying, stopping, or
mutating builds requires explicit authorization. Standalone Groovy cannot
faithfully reproduce CPS; validate changed call chains, repository tests,
small safe runtime probes, then an authorized representative Pipeline when
needed.

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
