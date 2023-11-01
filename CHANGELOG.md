# CHANGELOG

## Unreleased

### Added

### Changed

- Allow strings and symbols in deep ref paths.
- Throw an exception when the component graph contains a cycle.


## 2022-06-25 0.0.165

### Added

- `donut.system/stop-failed-system` function that attempts to stop a system that
  threw an exception when starting

### Changed

- `:before-` and `:after-` stages have been renamed to `:pre-` and `:post-`
