# Gradle Wrapper Neo

Gradle Wrapper Neo is a source-based replacement for the standard Gradle Wrapper. A project commits a single Java source file instead of `gradle-wrapper.jar`; the launch scripts compile the source on demand and cache the resulting JAR under the project's `.gradle/wrapper-neo` directory.

## Requirements

The plugin supports Gradle 8.0.2 and later. Running a generated wrapper requires a JDK because `GradleWrapperNeo.java` must be compiled on first use and whenever it changes.

## Applying the plugin

```kotlin
plugins {
    id("org.glavo.gradle-wrapper-neo") version "0.1.0"
}
```

Generate the wrapper with the current Gradle version:

```shell
./gradlew wrapperNeo
```

A different Gradle version or distribution type can be selected with task options:

```shell
./gradlew wrapperNeo --gradle-version 8.14.3 --distribution-type all
```

Unless a checksum is configured explicitly, the task downloads the matching `.sha256` file. Set `downloadDistributionSha256Sum = false` on the `wrapperNeo` task to disable this behavior.

On Windows, use `gradlew.bat`. When invoking it from PowerShell, quote complete property assignments that contain punctuation, for example `gradlew.bat "-Pversion=0.1.0"`.

The task creates these files:

- `gradlew`
- `gradlew.bat`
- `gradlew.ps1`
- `gradle/wrapper/GradleWrapperNeo.java`
- `gradle/wrapper/gradle-wrapper.properties`

It removes `gradle/wrapper/gradle-wrapper.jar` by default. Commit the generated source, properties, and launch scripts.

## Download mirrors

User-level mirror rules are read from `$GRADLE_USER_HOME/gradle-wrapper-neo.json`. Set `GRADLE_WRAPPER_NEO_CONFIG` to an absolute path to use another file.

```json
{
  "version": 1,
  "mirrors": [
    {
      "pattern": "^https://services\\.gradle\\.org/distributions/(.+)$",
      "replacement": "https://mirror.example.com/gradle/$1",
      "requireChecksum": true
    }
  ]
}
```

Rules are evaluated in order. Mirror URLs must use HTTPS, and the original distribution URL remains the final fallback. `requireChecksum` defaults to `true` so a mirror is not used unless `distributionSha256Sum` is configured.

## Building and publishing

```shell
./gradlew clean build :wrapper-plugin:validatePlugins
```

The `Verify` GitHub Actions workflow runs this validation on Ubuntu 24.04, macOS 15, and Windows Server 2022 for every branch push and pull request.

Validate the final Plugin Portal publication without uploading it:

```shell
./gradlew :wrapper-plugin:publishPlugins --validate-only "-Pversion=0.1.0"
```

The publish task checks credentials even in validation-only mode. Set `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` to the values provided by the Gradle Plugin Portal.

Repository releases can be published by configuring secrets with those names and pushing a version tag such as `v0.1.0`. The `Publish Plugin` workflow can also be started manually with a version input. It performs a full release build, validates the Plugin Portal publication, and then publishes the plugin.

## License

Gradle Wrapper Neo is available under the Apache License 2.0.
