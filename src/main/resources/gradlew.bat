@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  gradlew startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1
exit /b 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1
exit /b 1

:execute
@rem Setup the command line

set WRAPPER_DIR=%APP_HOME%\gradle\wrapper
set NEO_WORK_DIR=%WRAPPER_DIR%\.gradle-wrapper-neo
set NEO_SOURCE=%WRAPPER_DIR%\GradleWrapperNeo.java
set NEO_JAR=%NEO_WORK_DIR%\gradle-wrapper-neo.jar
set NEO_BOOTSTRAP_DIR=%NEO_WORK_DIR%\bootstrap\%RANDOM%-%RANDOM%
set NEO_CLASSES_DIR=%NEO_BOOTSTRAP_DIR%\classes

if not exist "%NEO_SOURCE%" goto missingNeoSource
if exist "%NEO_JAR%" goto executeNeoJar

if defined JAVA_HOME (
    set JAVAC_EXE=%JAVA_HOME%\bin\javac.exe
) else (
    set JAVAC_EXE=javac.exe
)

"%JAVAC_EXE%" -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto compileNeoSource

echo. 1>&2
echo ERROR: GradleWrapperNeo.java exists, but javac could not be found. 1>&2
echo. 1>&2
echo Please run this wrapper with a JDK, or provide %NEO_JAR%. 1>&2

"%COMSPEC%" /c exit 1
exit /b 1

:compileNeoSource
if exist "%NEO_CLASSES_DIR%" rmdir /s /q "%NEO_CLASSES_DIR%" >NUL 2>&1
mkdir "%NEO_CLASSES_DIR%" >NUL 2>&1
if %ERRORLEVEL% neq 0 (
    echo. 1>&2
    echo ERROR: Could not create temporary directory %NEO_CLASSES_DIR%. 1>&2
    "%COMSPEC%" /c exit 1
    exit /b 1
)

for /f "tokens=2 delims= " %%v in ('"%JAVAC_EXE%" -version 2^>^&1') do set JAVAC_VERSION=%%v
echo %JAVAC_VERSION% | findstr /b "1." >NUL
if %ERRORLEVEL% equ 0 (
    set JAVAC_TARGET_ARGS=-source 8 -target 8
) else (
    set JAVAC_TARGET_ARGS=--release 8
)

"%JAVAC_EXE%" %JAVAC_TARGET_ARGS% -encoding UTF-8 -d "%NEO_CLASSES_DIR%" "%NEO_SOURCE%"
if %ERRORLEVEL% equ 0 goto executeNeoBootstrap

rmdir /s /q "%NEO_CLASSES_DIR%" >NUL 2>&1
echo. 1>&2
echo ERROR: Could not compile %NEO_SOURCE%. 1>&2

"%COMSPEC%" /c exit 1
exit /b 1

:executeNeoBootstrap
@rem Execute GradleWrapperNeo from temporary classes. Java packages the final JAR.
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" "-Dorg.gradle.wrapper.neo.wrapper-dir=%WRAPPER_DIR%" "-Dgradle.wrapper.neo.bootstrap=true" -cp "%NEO_CLASSES_DIR%" GradleWrapperNeo %* & call :exitWithErrorLevel
goto :eof

:executeNeoJar
@rem Execute GradleWrapperNeo from the cached JAR.
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" "-Dorg.gradle.wrapper.neo.wrapper-dir=%WRAPPER_DIR%" -jar "%NEO_JAR%" %* & call :exitWithErrorLevel
goto :eof

:missingNeoSource
echo. 1>&2
echo ERROR: %NEO_SOURCE% was not found. 1>&2

"%COMSPEC%" /c exit 1
exit /b 1

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
