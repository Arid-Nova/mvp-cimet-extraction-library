@echo off
REM Maven wrapper launcher for Windows — invokes the wrapper main class directly
set DIR=%~dp0
set WRAPPER_JAR=%DIR%\.mvn\wrapper\maven-wrapper.jar

if not exist "%WRAPPER_JAR%" (
  echo ERROR: Maven wrapper jar not found: %WRAPPER_JAR%
  exit /b 1
)

java -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
