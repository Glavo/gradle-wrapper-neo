# Changelog

## 0.4.0 (Unreleased)

- Fixed cache validation to recognize paths that refer to the same JAR through short names or directory aliases.
- Added directory alias regression coverage and corrected cache path assertions to compare file identity.
- Enabled full exception details in Wrapper test failure logs.

---

## 0.3.0 (2026-11-11)

- Updated the Wrapper runtime and project build to Gradle 9.9.0.
- Redacted URL credentials in diagnostics and preserved filesystem errors when creating lock directories.
- Enabled native access in cached Wrapper JARs to avoid restricted-method warnings on Java 24 and later.
- Keyed Wrapper caches by source SHA-256 so upgrades persist on Windows without replacing a running JAR.

---

## 0.2.0 (2026-07-14)

### Improvements

- Expanded the README with project installation, Gradle plugin usage, download mirror configuration, and global installation instructions.

### Fixes

- Preserved the PowerShell caller's working directory when launching Gradle.

### Tests

- Added Windows launcher coverage for PowerShell working directory handling.

---

## 0.1.0 (2026-07-13)

Initial release.
