# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]


## 0.7.0 - 2026-10-04

### Added

- Add `connectToDockerContainer(name)` to create an explicit command handle with
  `exec`, `execStatus`, and `execStdout`, leaving ordinary commands on the agent.

### Changed

- Defer named-container validation for both Docker helpers until a command runs.
  Inspect the container for each command without caching its ID.
- Stop publishing `PIPELINE_DOCKER_CONTAINER_ID`,
  `PIPELINE_DOCKER_CONTAINER_NAME`, and `PIPELINE_DOCKER_CONTAINER_OS`.
- Provision and clean up Docker containers within the Jenkins integration tests
  instead of relying on pre-existing sidecars.


## 0.6.1 - 2026-09-09

### Fixed

- Pin the tested minimum versions of all direct Jenkins plugin dependencies so
  BOM updates cannot silently raise the requirements recorded in the HPI
  manifest.


## 0.6.0 - 2026-09-08

### Added

- Add an independent `failFast` option to `everyNode` as a named argument or
  third positional argument. It defaults to `false` for both sequential and
  parallel execution, allowing unaffected nodes to finish when another node
  fails.


## 0.5.1 - 2026-08-21

### Fixed

- Store `execStdout` capture and status files and Docker command PID files in
  the temporary directory associated with the current workspace, preventing
  command bookkeeping from appearing in working-directory output and avoiding
  stale `WORKSPACE_TMP` paths after `dir(...)`.

## 0.5.0 - 2026-08-19

### Added

- Add `nodeIfCurrentDoesNotMatch`, which reuses a matching current Jenkins node
  and otherwise performs a native `node(label)` allocation.
- Add `isWindows`, an invisible boolean inverse of Jenkins' native `isUnix`
  check.

## 0.4.0 - 2026-08-19

### Added

- Add `withEnvFile`, which parses a workspace dotenv file and applies its
  variables to a Pipeline body with lexical, nestable restoration.
- Support the de facto dotenv grammar, including quoted and multiline values,
  inline comments, `export`, UTF-8 byte-order marks, and mixed line endings.
- Record the dotenv file and applied assignments on the Pipeline graph without
  writing their values to the console log.

## 0.3.1 - 2026-08-18

### Fixed

- Isolate parallel `everyNode` CPS branches so each node has independent body
  state and durable steps abort cleanly without writing to completed branches.

## 0.3.0 - 2026-08-18

### Changed

- Run each `everyNode` body inside a real Jenkins `stage(env.NODE_NAME)` step so
  node-named stages behave correctly in Pipeline visualizations.

## 0.2.0 - 2026-08-18

### Added

- Add positional `everyNode(label, parallel)` arguments while retaining the
  existing named-argument form.

## 0.1.3 - 2026-08-18

### Fixed

- Preserve Linux container command exit statuses by waiting for the process
  launched through `setsid`.

## 0.1.2 - 2026-08-18

### Changed

- Display each `everyNode` body invocation as a Jenkins stage named after its
  concrete node.

## 0.1.1 - 2026-08-18

### Added

- Allow `everyNode` to omit its label and snapshot all online nodes.

### Changed

- Reuse a matching current node and let Jenkins select the next available node
  from the remaining sequential targets.

### Fixed

- Correct Linux and Windows Docker container platform detection.

## 0.1.0 - 2026-08-17

### Added

- Add durable `exec`, `execStatus`, and `execStdout` Pipeline steps for native
  Linux and Windows command execution.
- Add lexical, nestable `insideDockerContainer` routing for command steps,
  including an environment-variable allowlist and Linux and Windows sidecars.
- Add sequential and parallel `everyNode` execution across a snapshot of online
  nodes matching a Jenkins label expression.
- Add automated verification and tagged GitHub releases for the initial plugin.
