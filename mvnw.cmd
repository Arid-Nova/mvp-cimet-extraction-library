@echo off
REM Minimal Maven wrapper launcher for Windows
set DIR=%~dp0
set WRAPPER_JAR=%DIR%\.mvn\wrapper\maven-wrapper.jar

if not exist "%WRAPPER_JAR%" (
  echo ERROR: Maven wrapper jar not found: %WRAPPER_JAR%
  exit /b 1
)

java -jar "%WRAPPER_JAR%" %*
