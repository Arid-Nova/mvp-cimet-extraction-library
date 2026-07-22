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

if not defined JAVA_TOOL_OPTIONS (
  set JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED
) else (
  set JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% --enable-native-access=ALL-UNNAMED
)

java -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
