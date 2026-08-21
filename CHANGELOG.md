# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]


## 0.5.1 - 2026-08-21

- Store `execStdout` capture and status files and Docker command PID files in
  the temporary directory associated with the current workspace, preventing
  command bookkeeping from appearing in working-directory output and avoiding
  stale `WORKSPACE_TMP` paths after `dir(...)`.

## 0.5.0 - 2026-08-19

- Add `nodeIfCurrentDoesNotMatch`, which reuses a matching current Jenkins node
  and otherwise performs a native `node(label)` allocation.
- Add `isWindows`, an invisible boolean inverse of Jenkins' native `isUnix`
  check.

## 0.4.0 - 2026-08-19

- Add `withEnvFile`, which parses a workspace dotenv file and applies its
  variables to a Pipeline body with lexical, nestable restoration.
- Support the de facto dotenv grammar, including quoted and multiline values,
  inline comments, `export`, UTF-8 byte-order marks, and mixed line endings.
- Record the dotenv file and applied assignments on the Pipeline graph without
  writing their values to the console log.

## 0.3.1 - 2026-08-18

- Isolate parallel `everyNode` CPS branches so each node has independent body
  state and durable steps abort cleanly without writing to completed branches.

## 0.3.0 - 2026-08-18

- Run each `everyNode` body inside a real Jenkins `stage(env.NODE_NAME)` step so
  node-named stages behave correctly in Pipeline visualizations.

## 0.2.0 - 2026-08-18

- Add positional `everyNode(label, parallel)` arguments while retaining the
  existing named-argument form.

## 0.1.3 - 2026-08-18

- Preserve Linux container command exit statuses by waiting for the process
  launched through `setsid`.

## 0.1.2 - 2026-08-18

- Display each `everyNode` body invocation as a Jenkins stage named after its
  concrete node.

## 0.1.1 - 2026-08-18

- Allow `everyNode` to omit its label and snapshot all online nodes.
- Reuse a matching current node and let Jenkins select the next available node
  from the remaining sequential targets.
- Correct Linux and Windows Docker container platform detection.

## 0.1.0 - 2026-08-17

- Add durable `exec`, `execStatus`, and `execStdout` Pipeline steps for native
  Linux and Windows command execution.
- Add lexical, nestable `insideDockerContainer` routing for command steps,
  including an environment-variable allowlist and Linux and Windows sidecars.
- Add sequential and parallel `everyNode` execution across a snapshot of online
  nodes matching a Jenkins label expression.
- Add automated verification and tagged GitHub releases for the initial plugin.
