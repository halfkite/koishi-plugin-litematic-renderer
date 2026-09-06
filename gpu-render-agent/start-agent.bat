@echo off
setlocal EnableExtensions
cd /d "%~dp0"

rem The portable ZIP already has a jpackage launcher and bundled Java runtime.
if exist "%~dp0Litematic GPU Agent\Litematic GPU Agent.exe" (
  start "" "%~dp0Litematic GPU Agent\Litematic GPU Agent.exe" %*
  exit /b 0
)

rem Prefer the jpackage portable runtime, then a local JRE, then JAVA_HOME/system Java.
set "JAVA_EXE="
if exist "%~dp0Litematic GPU Agent\runtime\bin\java.exe" set "JAVA_EXE=%~dp0Litematic GPU Agent\runtime\bin\java.exe"
if not defined JAVA_EXE if exist "%~dp0runtime\bin\java.exe" set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for %%F in (java.exe) do set "JAVA_EXE=%%~$PATH:F"

set "JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-n "%~dp0Litematic GPU Agent\app\litematic-gpu-agent-*-all.jar" 2^>nul') do if not defined JAR set "JAR=%~dp0Litematic GPU Agent\app\%%F"
if not defined JAR for /f "delims=" %%F in ('dir /b /a-d /o-n "%~dp0litematic-gpu-agent-*-all.jar" 2^>nul') do if not defined JAR set "JAR=%~dp0%%F"
if not defined JAR for /f "delims=" %%F in ('dir /b /a-d /o-n "%~dp0build\libs\litematic-gpu-agent-*-all.jar" 2^>nul') do if not defined JAR set "JAR=%~dp0build\libs\%%F"
if not defined JAR for /f "delims=" %%F in ('dir /b /a-d /o-n "%~dp0build\distributions\litematic-gpu-agent-*-all.jar" 2^>nul') do if not defined JAR set "JAR=%~dp0build\distributions\%%F"

if not defined JAVA_EXE (
  echo Java 25 was not found. Install Java 25 or use the Windows portable package.
  pause
  exit /b 1
)
if not defined JAR (
  echo litematic-gpu-agent-*-all.jar was not found.
  pause
  exit /b 1
)

echo Java: %JAVA_EXE%
echo Agent JAR: %JAR%
"%JAVA_EXE%" -jar "%JAR%" %*
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" (
  echo Agent exited with code: %EXIT_CODE%
  pause
)
exit /b %EXIT_CODE%
