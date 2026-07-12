# Gradle Wrapper Neo

Gradle Wrapper Neo is an alternative to Gradle Wrapper. Its key features are:

1. No embedded binary files

   The traditional Gradle Wrapper requires projects to include a `gradle-wrapper.jar` binary, which is unacceptable for many projects.

   Gradle Wrapper Neo implements the functionality of Gradle Wrapper in a single Java file. Projects only need to include the `GradleWrapperNeo.java` source file, making it easier to audit and manage.

2. Configurable download mirrors

   In some regions, such as mainland China, downloading Gradle distributions through Gradle Wrapper can be slow.

   Gradle Wrapper Neo allows download mirrors to be configured in the user's home directory. This makes it possible to change the Gradle download source without modifying the project's `gradle-wrapper.properties` file, improving download speeds.

3. Drop-in replacement for Gradle Wrapper

   To migrate from Gradle Wrapper to Gradle Wrapper Neo, replace the Wrapper launchers, add `GradleWrapperNeo.java`, and remove `gradle-wrapper.jar`.
   No other changes are required. You can continue to build the project with `./gradlew` and manage the Gradle version through `gradle/wrapper/gradle-wrapper.properties`.

   Gradle Wrapper Neo also supports a non-embedded mode. You can install it on your computer and add its `gradlew` script to PATH.
   Then, in projects that still use Gradle Wrapper, run `gradlew` instead of `./gradlew`. Gradle Wrapper Neo will automatically take over the process,
   giving you access to features such as download mirrors without requiring any changes to the project.

## Install in a project

### Gradle plugin

The recommended installation method is the Gradle Wrapper Neo plugin. The plugin requires Gradle 8.0 or later.

Add the plugin to the root build:

```kotlin
plugins {
    id("org.glavo.gradle-wrapper-neo") version "0.1.0"
}
```

Then generate the Wrapper files with the existing Wrapper or a local Gradle installation:

```bash
./gradlew wrapperNeo --gradle-version 9.6.1
```

On Windows:

```powershell
./gradlew.bat wrapperNeo --gradle-version 9.6.1
```

The `wrapperNeo` task writes these files:

```text
gradlew
gradlew.bat
gradlew.ps1
gradle/wrapper/GradleWrapperNeo.java
gradle/wrapper/gradle-wrapper.properties
```

By default, it also removes `gradle/wrapper/gradle-wrapper.jar` and downloads the SHA-256 checksum published for the selected Gradle distribution.

### Manual migration

You can migrate an existing Wrapper without applying the plugin. Run the appropriate commands from the project root. They preserve the existing `gradle/wrapper/gradle-wrapper.properties` file.

#### Bash

```bash
version=0.1.0
base_url="https://github.com/Glavo/gradle-wrapper-neo/releases/download/v$version"
mkdir -p gradle/wrapper
curl -fsSL "$base_url/gradlew" -o gradlew
curl -fsSL "$base_url/gradlew.bat" -o gradlew.bat
curl -fsSL "$base_url/gradlew.ps1" -o gradlew.ps1
curl -fsSL "$base_url/GradleWrapperNeo.java" -o gradle/wrapper/GradleWrapperNeo.java
chmod +x gradlew

# Gradle Wrapper Neo uses the Java source file instead of the legacy Wrapper JAR.
rm -f gradle/wrapper/gradle-wrapper.jar
```

#### PowerShell

```powershell
$version = '0.1.0'
$baseUrl = "https://github.com/Glavo/gradle-wrapper-neo/releases/download/v$version"
New-Item -ItemType Directory -Force -Path 'gradle/wrapper' | Out-Null
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/gradlew" -OutFile 'gradlew'
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/gradlew.bat" -OutFile 'gradlew.bat'
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/gradlew.ps1" -OutFile 'gradlew.ps1'
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/GradleWrapperNeo.java" -OutFile 'gradle/wrapper/GradleWrapperNeo.java'

# Gradle Wrapper Neo uses the Java source file instead of the legacy Wrapper JAR.
Remove-Item -LiteralPath 'gradle/wrapper/gradle-wrapper.jar' -Force -ErrorAction SilentlyContinue
```

## Configure the `wrapperNeo` task

The task defaults to the Gradle version running the build and the binary-only distribution. For reproducible builds, select an exact Gradle version.

The following Kotlin DSL example shows the most commonly used options:

```kotlin
import org.glavo.gradle.wrapper.neo.plugin.WrapperNeo

plugins {
    id("org.glavo.gradle-wrapper-neo") version "0.1.0"
}

tasks.named<WrapperNeo>("wrapperNeo") {
    gradleVersion.set("9.6.1")
    distributionType.set(WrapperNeo.DistributionType.BIN)
    downloadDistributionSha256Sum.set(true)
    networkTimeout.set(15_000)
    retries.set(2)
    retryBackOffMs.set(500)
    removeLegacyWrapperJar.set(true)
}
```

The task exposes these properties:

| Property | Default | Description |
| --- | --- | --- |
| `gradleVersion` | Current Gradle version | Version used to construct the distribution URL. Supports exact versions; `latest`, `release-candidate`, `release-milestone`, `release-nightly`, and `nightly`; and major or major/minor selectors for Gradle 9 or later. |
| `distributionType` | `BIN` | `BIN` or `ALL`. |
| `distributionUrl` | Not set | Complete distribution URL. When set, it overrides `gradleVersion` and `distributionType`. |
| `distributionSha256Sum` | Not set | Expected SHA-256 checksum. An explicitly configured value takes precedence over downloading one. |
| `downloadDistributionSha256Sum` | `true` | Downloads the checksum from the distribution URL with `.sha256` appended before any query or fragment. |
| `distributionBase` | `GRADLE_USER_HOME` | Base directory for the installed distribution: `GRADLE_USER_HOME` or `PROJECT`. |
| `distributionPath` | `wrapper/dists` | Path below `distributionBase`. |
| `archiveBase` | `GRADLE_USER_HOME` | Base directory for downloaded distribution archives: `GRADLE_USER_HOME` or `PROJECT`. |
| `archivePath` | `wrapper/dists` | Path below `archiveBase`. |
| `networkTimeout` | `10000` | Connection and read timeout in milliseconds. |
| `retries` | `0` | Number of additional distribution download attempts. |
| `retryBackOffMs` | `500` | Initial delay between download attempts, in milliseconds. |
| `removeLegacyWrapperJar` | `true` | Removes `gradle/wrapper/gradle-wrapper.jar` after generating the new Wrapper. |

The commonly used properties are also available as task options:

```text
--gradle-version
--distribution-type
--gradle-distribution-url
--gradle-distribution-sha256-sum
--network-timeout
```

Run `./gradlew help --task wrapperNeo` for their descriptions.

## Configure download mirrors

Mirror configuration is user-specific and does not modify the project. By default, Gradle Wrapper Neo loads:

```text
$GRADLE_USER_HOME/gradle-wrapper-neo.json
```

The effective Gradle user home respects `-g`, `--gradle-user-home`, the `gradle.user.home` system property, and the `GRADLE_USER_HOME` environment variable. To use another configuration file, set `GRADLE_WRAPPER_NEO_CONFIG` to its absolute path.

The configuration format is versioned JSON:

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

Replace `mirror.example.com` with the mirror service you use. Each mirror supports these fields:

| Field | Required | Default | Description |
| --- | --- | --- | --- |
| `pattern` | Yes | — | Java regular expression matched against the complete distribution URL. |
| `replacement` | Yes | — | Java regular-expression replacement. Capture references such as `$1` are supported. |
| `enabled` | No | `true` | Set to `false` to disable this entry without removing it. |
| `requireChecksum` | No | `true` | Applies the mirror only when `distributionSha256Sum` is present. |

Mirror entries are tried in declaration order. Duplicate results are removed, and the original distribution URL is always retained as the final fallback. A replacement must produce an absolute HTTPS URL with a host and no fragment.

Keeping `requireChecksum` enabled is recommended. The `wrapperNeo` task downloads and writes the official checksum by default, so mirrors normally work without additional project configuration.

## Global installation

Gradle Wrapper Neo can also be installed outside individual projects. A global installation uses this layout:

```text
gradle-wrapper-neo/
├── gradlew
├── gradlew.bat
├── gradlew.ps1
└── gradle/
    └── wrapper/
        └── GradleWrapperNeo.java
```

Download the four files from the [latest release](https://github.com/Glavo/gradle-wrapper-neo/releases/latest), preserve this layout, and then:

- On POSIX systems, make `gradlew` executable and create a `gradlew` symlink in a directory on `PATH`.
- On Windows, add the installation root containing `gradlew.bat` and `gradlew.ps1` to `PATH`.

Run `gradlew` from a Gradle project or one of its subdirectories. The global launcher searches upward from the current working directory for `gradle/wrapper/gradle-wrapper.properties`. If the project contains `gradle/wrapper/GradleWrapperNeo.java`, that source takes precedence; otherwise, the launcher uses its globally installed source file. The project's existing Wrapper files are not modified.

## Runtime files and requirements

The first launch, and any launch that needs to rebuild the cached Wrapper after a source update, compiles `GradleWrapperNeo.java`. A JDK with `javac` must therefore be available. The generated source targets Java 8, but the selected Gradle version may require a newer JVM.

Compiled Wrapper code is cached inside the project at:

```text
.gradle/wrapper-neo/gradle-wrapper-neo.jar
```

This runtime cache should not be committed. Delete `.gradle/wrapper-neo` to force the Wrapper source to be compiled again. Gradle distributions and their archives continue to use the locations configured in `gradle-wrapper.properties`, normally `$GRADLE_USER_HOME/wrapper/dists`.

On Windows, `gradlew.bat` delegates to `gradlew.ps1`; keep both files together. Windows PowerShell 5.1 and PowerShell 7 are supported.

## License

Gradle Wrapper Neo is available under the [Apache License 2.0](LICENSE).
