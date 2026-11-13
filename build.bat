@echo off
rem Appraan build — plain javac + jar, zero dependencies. Windows equivalent of build.sh.
setlocal
cd /d "%~dp0"

if defined JAVA_HOME (
  set "JAVAC=%JAVA_HOME%\bin\javac.exe"
  set "JAR=%JAVA_HOME%\bin\jar.exe"
) else (
  set "JAVAC=javac"
  set "JAR=jar"
)

echo Using javac: %JAVAC%
if exist build rmdir /s /q build
if exist dist rmdir /s /q dist
mkdir build\classes
mkdir dist

dir /s /b src\main\java\*.java > build\sources.txt
"%JAVAC%" -source 21 -target 21 --add-modules jdk.attach,jdk.jfr,jdk.management --add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1

"%JAR%" --create --file dist\appraan.jar --main-class dev.appraan.cli.Appraan -C build\classes .
if errorlevel 1 exit /b 1

echo Built dist\appraan.jar
endlocal
