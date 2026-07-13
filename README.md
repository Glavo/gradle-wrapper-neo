# Gradle Wrapper Neo

[![Gradle Plugin](https://img.shields.io/gradle-plugin-portal/v/org.glavo.gradle-wrapper-neo?label=Gradle%20Plugin%20Portal)](https://plugins.gradle.org/plugin/org.glavo.gradle-wrapper-neo)
[![License](https://img.shields.io/github/license/Glavo/gradle-wrapper-neo)](https://github.com/Glavo/gradle-wrapper-neo/blob/main/LICENSE)

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

## Getting started

### Install in a project

You can replace the existing Gradle Wrapper in a project with the following shell commands:

Bash:

```bash
base_url="https://github.com/Glavo/gradle-wrapper-neo/releases/download/v0.2.0"
mkdir -p gradle/wrapper
curl -fsSL "$base_url/gradlew" -o gradlew
curl -fsSL "$base_url/gradlew.bat" -o gradlew.bat
curl -fsSL "$base_url/gradlew.ps1" -o gradlew.ps1
curl -fsSL "$base_url/GradleWrapperNeo.java" -o gradle/wrapper/GradleWrapperNeo.java
chmod +x gradlew
rm -f gradle/wrapper/gradle-wrapper.jar
```

PowerShell:

```powershell
$baseUrl = 'https://github.com/Glavo/gradle-wrapper-neo/releases/download/v0.2.0'
New-Item -ItemType Directory -Force -Path 'gradle/wrapper' | Out-Null
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/gradlew" -OutFile 'gradlew'
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/gradlew.bat" -OutFile 'gradlew.bat'
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/gradlew.ps1" -OutFile 'gradlew.ps1'
Invoke-WebRequest -UseBasicParsing -Uri "$baseUrl/GradleWrapperNeo.java" -OutFile 'gradle/wrapper/GradleWrapperNeo.java'
Remove-Item -LiteralPath 'gradle/wrapper/gradle-wrapper.jar' -Force -ErrorAction SilentlyContinue
```

### Add the Gradle plugin

Gradle Wrapper Neo provides a Gradle plugin as an alternative to Gradle's built-in `wrapper` task.

Add it to the root project's `build.gradle.kts` file:

```kotlin
plugins {
    id("org.glavo.gradle-wrapper-neo") version "0.2.0"
}
```

The plugin adds a `wrapperNeo` task that can generate the Wrapper files and update the Gradle version:

```bash
./gradlew wrapperNeo --gradle-version 9.6.1
```

## Configure download mirrors

If downloading Gradle is slow on your network, you can configure Gradle Wrapper Neo to download distributions from a mirror.

By default, Gradle Wrapper Neo loads its configuration from `$GRADLE_USER_HOME/gradle-wrapper-neo.json`, normally `~/.gradle/gradle-wrapper-neo.json` when no custom Gradle user home is configured.

For example, to use the [Tencent Cloud Gradle mirror](https://mirrors.cloud.tencent.com/gradle/), create `$GRADLE_USER_HOME/gradle-wrapper-neo.json` with the following content:

```json
{
  "version": 1,
  "mirrors": [
    {
      "pattern": "^https://services\\.gradle\\.org/distributions/(.+)$",
      "replacement": "https://mirrors.cloud.tencent.com/gradle/$1",
      "requireChecksum": true
    }
  ]
}
```

With `requireChecksum` set to `true`, the mirror is used only when `gradle-wrapper.properties` contains `distributionSha256Sum`. The `wrapperNeo` task writes this checksum by default. The original distribution URL is always retained as a fallback.

You can create this configuration file with one of the following scripts. Existing content at the target path will be replaced.

<details>
<summary>Bash</summary>

```bash
gradle_user_home=${GRADLE_USER_HOME:-"$HOME/.gradle"}
config_file=$gradle_user_home/gradle-wrapper-neo.json

mkdir -p "$gradle_user_home"
cat > "$config_file" <<'EOF'
{
  "version": 1,
  "mirrors": [
    {
      "pattern": "^https://services\\.gradle\\.org/distributions/(.+)$",
      "replacement": "https://mirrors.cloud.tencent.com/gradle/$1",
      "requireChecksum": true
    }
  ]
}
EOF
```

</details>


<details>
<summary>PowerShell</summary>

```powershell
if ([string]::IsNullOrEmpty($env:GRADLE_USER_HOME)) {
    $gradleUserHome = Join-Path $HOME '.gradle'
} else {
    $gradleUserHome = $env:GRADLE_USER_HOME
}
$configFile = Join-Path $gradleUserHome 'gradle-wrapper-neo.json'

$json = @'
{
  "version": 1,
  "mirrors": [
    {
      "pattern": "^https://services\\.gradle\\.org/distributions/(.+)$",
      "replacement": "https://mirrors.cloud.tencent.com/gradle/$1",
      "requireChecksum": true
    }
  ]
}
'@

New-Item -ItemType Directory -Force -Path $gradleUserHome | Out-Null
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($configFile, $json, $utf8WithoutBom)
```

</details>

## License

Gradle Wrapper Neo is available under the [Apache License 2.0](LICENSE).
