@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title SNAKE NET - build
cd /d "%~dp0"

set "JDK=C:\Program Files\Java\jdk-25.0.5.0.3\bin\"
set "MAJOR=25"

echo   JDK !MAJOR! found.
echo.
echo   [1/4] Compiling NetCore engine...
if exist "out\engine" rmdir /s /q "out\engine"
mkdir "out\engine" 2>nul
> "%TEMP%\netcore_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b engine\src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)
"%JDK%javac.exe" --release 25 -encoding UTF-8 -d "out\engine" @"%TEMP%\netcore_sources.txt"
if errorlevel 1 goto :fail

echo   [2/4] Compiling the game...
if exist "out\game" rmdir /s /q "out\game"
mkdir "out\game" 2>nul
> "%TEMP%\snake_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b game\src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)
"%JDK%javac.exe" --release 25 -encoding UTF-8 -cp "out\engine" -d "out\game" @"%TEMP%\snake_sources.txt"
if errorlevel 1 goto :fail

echo   [3/4] Building NetCore.jar...
del /q "NetCore.jar" 2>nul
"%JDK%jar.exe" --create --file "NetCore.jar" -C "out\engine" .
if errorlevel 1 goto :fail

echo   [4/4] Building SnakeNET.jar...
del /q "SnakeNET.jar" 2>nul
"%JDK%jar.exe" --create --file "SnakeNET.jar" --manifest "manifest.txt" -C "out\game" .
if errorlevel 1 goto :fail

echo.
echo   Done: NetCore.jar (engine) + SnakeNET.jar (game)
echo   Run: run.bat
echo.
pause
exit /b 0

:fail
echo.
echo   Build failed.
pause
exit /b 1
