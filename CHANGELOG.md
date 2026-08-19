# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]


## 0.5.0 - 2026-08-19

- Add `nodeIfCurrentDoesNotMatch`, which reuses a matching current Jenkins node
  and otherwise performs a native `node(label)` allocation.
- Add `isWindows`, an invisible boolean inverse of Jenkins' native `isUnix`
  check.
