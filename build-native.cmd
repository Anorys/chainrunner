@echo off
rem Builds target\chainrunner.exe with GraalVM native-image.
rem Run from an "x64 Native Tools Command Prompt for VS", with JAVA_HOME on GraalVM for JDK 21.
cd /d "%~dp0"
if exist .mvn\wrapper\maven-wrapper.properties (set MVN=mvnw.cmd) else (set MVN=mvn)
call %MVN% -Pnative -DskipTests package || exit /b 1
target\chainrunner.exe --version
