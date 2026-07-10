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
@rem  gradlew forwarding script for Windows
@rem
@rem ##########################################################################

setlocal EnableExtensions

set "POWERSHELL_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if exist "%POWERSHELL_EXE%" goto execute

set "POWERSHELL_EXE=powershell.exe"
"%POWERSHELL_EXE%" -NoLogo -NoProfile -NonInteractive -Command "exit 0" >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

set "POWERSHELL_EXE=pwsh.exe"
"%POWERSHELL_EXE%" -NoLogo -NoProfile -NonInteractive -Command "exit 0" >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: PowerShell could not be found. 1>&2
echo. 1>&2
echo Please install Windows PowerShell 5.1 or PowerShell 7 and make it available in PATH. 1>&2

endlocal
exit /b 1

:execute
"%POWERSHELL_EXE%" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0gradlew.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %EXIT_CODE%
