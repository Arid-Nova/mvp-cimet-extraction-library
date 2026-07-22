@echo off
REM Maven wrapper launcher for Windows — invokes the wrapper main class directly
set DIR=%~dp0
set WRAPPER_JAR=%DIR%\.mvn\wrapper\maven-wrapper.jar

if not exist "%WRAPPER_JAR%" (
  echo ERROR: Maven wrapper jar not found: %WRAPPER_JAR%
  exit /b 1
)

if not defined MAVEN_OPTS (
  set MAVEN_OPTS=-Dmaven.multiModuleProjectDirectory=%CD%
) else (
  set MAVEN_OPTS=%MAVEN_OPTS% -Dmaven.multiModuleProjectDirectory=%CD%
)

REM NOTE: JAVA_TOOL_OPTIONS is intentionally not set here. The devcontainer provides it
REM via containerEnv so it applies to all Java processes in the container.

java -Dmaven.multiModuleProjectDirectory=%CD% -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
