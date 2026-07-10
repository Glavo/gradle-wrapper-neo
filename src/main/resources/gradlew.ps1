#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
#

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$script:WrapperExitCode = 1

$script:AppBaseName = [IO.Path]::GetFileNameWithoutExtension($MyInvocation.MyCommand.Name)
$script:LauncherHome = [IO.Path]::GetFullPath($PSScriptRoot)

function Split-ArgumentString {
    param([string] $Value)

    if ($null -eq $Value) {
        return @()
    }

    $result = New-Object 'System.Collections.Generic.List[string]'
    $currentOption = New-Object Text.StringBuilder
    [char] $currentQuote = [char] 0
    $insideQuote = $false
    $hasOption = $false

    for ($index = 0; $index -lt $Value.Length; $index++) {
        [char] $character = $Value[$index]
        if ((-not $insideQuote) -and [char]::IsWhiteSpace($character)) {
            if ($hasOption) {
                [void] $result.Add($currentOption.ToString())
                [void] $currentOption.Clear()
                $hasOption = $false
            }
        } elseif ((-not $insideQuote) -and (($character -eq [char] 34) -or ($character -eq [char] 39))) {
            $currentQuote = $character
            $insideQuote = $true
            $hasOption = $true
        } elseif ($insideQuote -and ($character -eq $currentQuote)) {
            $insideQuote = $false
        } else {
            [void] $currentOption.Append($character)
            $hasOption = $true
        }
    }

    if ($hasOption) {
        [void] $result.Add($currentOption.ToString())
    }

    return $result.ToArray()
}

function Resolve-JavaExecutable {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHome = $env:JAVA_HOME.Trim([char] 34)
        $javaExecutable = Join-Path $javaHome 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
            throw "JAVA_HOME is set to an invalid directory: $javaHome"
        }
        return [IO.Path]::GetFullPath($javaExecutable)
    }

    $javaCommand = Get-Command -Name 'java.exe' -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $javaCommand) {
        throw "JAVA_HOME is not set and no 'java' command could be found in PATH."
    }
    return $javaCommand.Path
}

function Resolve-JavacExecutable {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaHome = $env:JAVA_HOME.Trim([char] 34)
        $javacExecutable = Join-Path $javaHome 'bin\javac.exe'
        if (Test-Path -LiteralPath $javacExecutable -PathType Leaf) {
            return [IO.Path]::GetFullPath($javacExecutable)
        }
    } else {
        $javacCommand = Get-Command -Name 'javac.exe' -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($null -ne $javacCommand) {
            return $javacCommand.Path
        }
    }

    throw 'GradleWrapperNeo.java exists, but javac could not be found. Run this wrapper with a JDK so the source can be compiled.'
}

function Find-AppHome {
    param([string] $LauncherHome)

    $launcherProperties = Join-Path $LauncherHome 'gradle\wrapper\gradle-wrapper.properties'
    if (Test-Path -LiteralPath $launcherProperties -PathType Leaf) {
        return $LauncherHome
    }

    $currentLocation = Get-Location
    if ($currentLocation.Provider.Name -ne 'FileSystem') {
        throw "The current location is not a file system directory: $currentLocation"
    }

    $searchDirectory = Get-Item -LiteralPath $currentLocation.Path
    $searchStart = $searchDirectory.FullName
    while ($null -ne $searchDirectory) {
        $propertiesFile = Join-Path $searchDirectory.FullName 'gradle\wrapper\gradle-wrapper.properties'
        if (Test-Path -LiteralPath $propertiesFile -PathType Leaf) {
            return $searchDirectory.FullName
        }
        $searchDirectory = $searchDirectory.Parent
    }

    throw "Could not find gradle/wrapper/gradle-wrapper.properties searching from '$searchStart'."
}

function ConvertTo-JavaPath {
    param([string] $Path)

    return $Path.Replace('\', '/')
}

function Compile-WrapperSource {
    param(
        [string] $SourceFile,
        [string] $ClassesDirectory,
        [string] $BootstrapDirectory
    )

    $javacExecutable = Resolve-JavacExecutable
    [void] [IO.Directory]::CreateDirectory($ClassesDirectory)

    $versionOutput = & $javacExecutable -version 2>&1
    $versionExitCode = $LASTEXITCODE
    if ($versionExitCode -ne 0) {
        Remove-Item -LiteralPath $BootstrapDirectory -Recurse -Force -ErrorAction SilentlyContinue
        throw "Could not run javac at '$javacExecutable'."
    }
    $versionText = ($versionOutput | Out-String).Trim()

    if ($versionText.StartsWith('javac 1.')) {
        [string[]] $targetArguments = @('-source', '8', '-target', '8')
    } else {
        [string[]] $targetArguments = @('--release', '8')
    }

    [string[]] $compileArguments = @(
        $targetArguments
        '-encoding'
        'UTF-8'
        '-d'
        $ClassesDirectory
        $SourceFile
    )

    & $javacExecutable @compileArguments
    $compileExitCode = $LASTEXITCODE
    if ($compileExitCode -ne 0) {
        Remove-Item -LiteralPath $BootstrapDirectory -Recurse -Force -ErrorAction SilentlyContinue
        throw "Could not compile '$SourceFile'."
    }
}

function Invoke-GradleWrapper {
    param([string[]] $GradleArguments)

    $appHome = Find-AppHome -LauncherHome $script:LauncherHome
    $launcherWrapperDirectory = Join-Path $script:LauncherHome 'gradle\wrapper'
    $projectSource = Join-Path $appHome 'gradle\wrapper\GradleWrapperNeo.java'
    if (Test-Path -LiteralPath $projectSource -PathType Leaf) {
        $sourceFile = $projectSource
    } else {
        $sourceFile = Join-Path $launcherWrapperDirectory 'GradleWrapperNeo.java'
    }
    if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        throw "Wrapper source file '$sourceFile' was not found."
    }

    $workDirectory = Join-Path $appHome '.gradle\wrapper-neo'
    $jarFile = Join-Path $workDirectory 'gradle-wrapper-neo.jar'
    $bootstrapDirectory = Join-Path (Join-Path $workDirectory 'bootstrap') ([Guid]::NewGuid().ToString('N'))
    $classesDirectory = Join-Path $bootstrapDirectory 'classes'

    $javaExecutable = Resolve-JavaExecutable
    [string[]] $jvmArguments = @('-Xmx64m', '-Xms64m')
    foreach ($optionSource in @($env:JAVA_OPTS, $env:GRADLE_OPTS)) {
        foreach ($option in @(Split-ArgumentString -Value $optionSource)) {
            $jvmArguments += $option
        }
    }

    $javaAppHome = ConvertTo-JavaPath -Path $appHome
    $javaSourceFile = ConvertTo-JavaPath -Path $sourceFile
    $javaJarFile = ConvertTo-JavaPath -Path $jarFile
    $javaClassesDirectory = ConvertTo-JavaPath -Path $classesDirectory

    [string[]] $javaArguments = @(
        $jvmArguments
        "-Dorg.gradle.appname=$script:AppBaseName"
        "-Dorg.gradle.wrapper.neo.app-home=$javaAppHome"
        "-Dorg.gradle.wrapper.neo.source-file=$javaSourceFile"
        "-Dorg.gradle.wrapper.neo.jar-file=$javaJarFile"
    )

    if (Test-Path -LiteralPath $jarFile -PathType Leaf) {
        $javaArguments += @('-jar', $javaJarFile)
    } else {
        Compile-WrapperSource -SourceFile $sourceFile -ClassesDirectory $classesDirectory -BootstrapDirectory $bootstrapDirectory
        $javaArguments += @(
            '-Dgradle.wrapper.neo.bootstrap=true'
            '-cp'
            $javaClassesDirectory
            'GradleWrapperNeo'
        )
    }

    $javaArguments += $GradleArguments
    & $javaExecutable @javaArguments
    $script:WrapperExitCode = $LASTEXITCODE
}

try {
    [string[]] $gradleArguments = @($args)
    Invoke-GradleWrapper -GradleArguments $gradleArguments
    exit $script:WrapperExitCode
} catch {
    [Console]::Error.WriteLine()
    [Console]::Error.WriteLine('ERROR: ' + $_.Exception.Message)
    [Console]::Error.WriteLine()
    exit 1
}
