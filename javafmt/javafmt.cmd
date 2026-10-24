@echo off
setlocal
set "JAVAFMT_ROOT=%~dp0"
if defined JAVAFMT_JAVA (
  set "JAVAFMT_RUNTIME=%JAVAFMT_JAVA%"
) else if exist "%JAVAFMT_ROOT%runtime\bin\java.exe" (
  set "JAVAFMT_RUNTIME=%JAVAFMT_ROOT%runtime\bin\java.exe"
) else if defined JAVA_HOME (
  set "JAVAFMT_RUNTIME=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVAFMT_RUNTIME=java"
)
if exist "%JAVAFMT_ROOT%lib\javafmt.jar" (
  set "JAVAFMT_ARCHIVE=%JAVAFMT_ROOT%lib\javafmt.jar"
) else (
  set "JAVAFMT_ARCHIVE=%JAVAFMT_ROOT%javafmt-cli\target\javafmt.jar"
)
if not exist "%JAVAFMT_ARCHIVE%" (
  echo Build javafmt first: mvnw.cmd -q -pl javafmt-cli -am package 1>&2
  exit /b 2
)
"%JAVAFMT_RUNTIME%" -Xmx512m -jar "%JAVAFMT_ARCHIVE%" %*
exit /b %ERRORLEVEL%
