@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title SNAKE NET - build
cd /d "%~dp0"

set "JDK=C:\Program Files\Java\jdk-25.0.5.0.3\bin\"
set "MAJOR=25"
set "EXTRA_CP="
if exist "lib\*.jar" set "EXTRA_CP=lib\*;"

echo   JDK !MAJOR! found.
echo.
echo   [1/6] Compiling NetCore engine...
if exist "out\engine" rmdir /s /q "out\engine"
mkdir "out\engine" 2>nul
> "%TEMP%\netcore_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b engine\src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)
"%JDK%javac.exe" --release 25 -encoding UTF-8 -cp "!EXTRA_CP!" -d "out\engine" @"%TEMP%\netcore_sources.txt"
if errorlevel 1 goto :fail

echo   [2/6] Compiling the snake game...
if exist "out\snake" rmdir /s /q "out\snake"
mkdir "out\snake" 2>nul
> "%TEMP%\snake_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b examples\snake\src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)
"%JDK%javac.exe" --release 25 -encoding UTF-8 -cp "out\engine;!EXTRA_CP!" -d "out\snake" @"%TEMP%\snake_sources.txt"
if errorlevel 1 goto :fail

echo   [3/6] Compiling the chess game...
if exist "out\chess" rmdir /s /q "out\chess"
mkdir "out\chess" 2>nul
> "%TEMP%\chess_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b examples\chess\src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)
"%JDK%javac.exe" --release 25 -encoding UTF-8 -cp "out\engine;!EXTRA_CP!" -d "out\chess" @"%TEMP%\chess_sources.txt"
if errorlevel 1 goto :fail

echo   [4/6] Building NetCore.jar...
del /q "NetCore.jar" 2>nul
"%JDK%jar.exe" --create --file "NetCore.jar" -C "out\engine" .
if errorlevel 1 goto :fail

echo   [5/6] Building SnakeNET.jar...
del /q "SnakeNET.jar" 2>nul
"%JDK%jar.exe" --create --file "SnakeNET.jar" --manifest "manifest-snake.txt" -C "out\snake" .
if errorlevel 1 goto :fail

echo   [6/6] Building ChessNET.jar...
del /q "ChessNET.jar" 2>nul
"%JDK%jar.exe" --create --file "ChessNET.jar" --manifest "manifest-chess.txt" -C "out\chess" .
if errorlevel 1 goto :fail

echo.
echo   Done: NetCore.jar (engine)
echo         SnakeNET.jar (snake client + server)
echo         ChessNET.jar (chess client + server)
echo   Run: run-snake.bat / run-chess.bat
echo   Server: server-snake.bat / server-chess.bat
echo.
pause
exit /b 0

:fail
echo.
echo   Build failed.
pause
exit /b 1
