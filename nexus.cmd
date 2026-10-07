@echo off
rem Starts NEXUS from the build output (run "mvnw package -DskipTests" first).
rem Uses %NEXUS_JAVA% or %JAVA_HOME% - a JDK 25 or newer.
setlocal
set "HERE=%~dp0"
if defined NEXUS_JAVA (set "JAVA=%NEXUS_JAVA%") else if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" --add-modules jdk.incubator.vector --enable-native-access=javafx.graphics,nexus.engines,ALL-UNNAMED -XX:+UseZGC ^
  -p "%HERE%nexus-app\target\lib;%HERE%nexus-app\target\nexus-app-0.1.0.jar" ^
  -m nexus.app/nexus.app.NexusApp %*
