@echo off
chcp 65001 >nul
title SNAKE NET - chess server
cd /d "%~dp0"

if not exist "ChessNET.jar" (
  echo   ChessNET.jar not found. Run build.bat first.
  pause
  exit /b 1
)

set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA set "JAVA=C:\Program Files\Java\jdk-25.0.5.0.3\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"

"%JAVA%" -cp "ChessNET.jar;NetCore.jar" snakenet.chess.ChessServer --port=45801
echo.
echo   Chess server stopped.
pause
