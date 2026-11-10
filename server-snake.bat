@echo off
chcp 65001 >nul
title SNAKE NET - snake server
cd /d "%~dp0"

if not exist "SnakeNET.jar" (
  echo   SnakeNET.jar not found. Run build.bat first.
  pause
  exit /b 1
)

set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA set "JAVA=C:\Program Files\Java\jdk-25.0.5.0.3\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

"%JAVA%" -cp "SnakeNET.jar;NetCore.jar" snakenet.server.StandaloneServer --game=snake --port=45800
echo.
echo   Snake server stopped.
pause
