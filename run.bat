@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title SNAKE NET
cd /d "%~dp0"

set "GAME=snake"
for %%a in (%*) do (
  if "%%a"=="--game=chess" set "GAME=chess"
)
if "!GAME!"=="chess" (
  set "GAMEJAR=ChessNET.jar"
  set "GAMEMAIN=snakenet.chess.ChessMain"
) else (
  set "GAMEJAR=SnakeNET.jar"
  set "GAMEMAIN=snakenet.snake.SnakeMain"
)

if not exist "!GAMEJAR!" (
  echo.
  echo   !GAMEJAR! not found.
  echo   Run build.bat first - it builds the engine and both games.
  echo.
  pause
  exit /b 1
)
if not exist "NetCore.jar" (
  echo.
  echo   NetCore.jar not found - this is the network engine, the game cannot start without it.
  echo   Run build.bat.
  echo.
  pause
  exit /b 1
)

set "JAVA="
set "JAVA_VER="

if defined JAVA_HOME call :check "%JAVA_HOME%\bin\java.exe"

if not defined JAVA (
  for /f "delims=" %%j in ('where java 2^>nul') do (
    if not defined JAVA call :check "%%j"
  )
)

if not defined JAVA call :deep "C:\Program Files\Java"
if not defined JAVA call :deep "C:\Program Files\Eclipse Adoptium"
if not defined JAVA call :deep "C:\Program Files\Microsoft"
if not defined JAVA call :deep "C:\Program Files (x86)\Java"
if not defined JAVA call :deep "%LOCALAPPDATA%\Programs\Eclipse Adoptium"
if not defined JAVA call :deep "%USERPROFILE%\.jdks"
if not defined JAVA call :deep "%APPDATA%\.minecraft\runtime"
if not defined JAVA call :deep "%APPDATA%\KLauncher\runtime"
if not defined JAVA call :deep "%USERPROFILE%\.vscode\extensions"

if not defined JAVA (
  echo.
  echo   Java 25 or newer was not found.
  echo.
  echo   Download and install free Java 25+ from:
  echo     https://adoptium.net/temurin/releases/?version=25
  echo   then run run.bat again.
  echo.
  pause
  exit /b 1
)

echo   Starting on Java !JAVA_VER! ...
set "JAVAW=%JAVA:java.exe=javaw.exe%"
set "EXTRA_CP="
if exist "lib\*.jar" set "EXTRA_CP=;lib\*"
if exist "%JAVAW%" (
  start "" "%JAVAW%" -cp "!GAMEJAR!;NetCore.jar!EXTRA_CP!" !GAMEMAIN! %*
) else (
  start "" "%JAVA%" -cp "!GAMEJAR!;NetCore.jar!EXTRA_CP!" !GAMEMAIN! %*
)
exit /b 0

:check
if not exist "%~1" exit /b 0
set "MAJOR="
set "FIRST=0"
"%~1" -version > "%TEMP%\snakenet_java.txt" 2>&1
for /f "usebackq tokens=1,2,3,4" %%a in ("%TEMP%\snakenet_java.txt") do (
  if !FIRST!==0 (
    call :take "%%a"
    if not defined MAJOR call :take "%%b"
    if not defined MAJOR call :take "%%c"
    if not defined MAJOR call :take "%%d"
    set "FIRST=1"
  )
)
if not defined MAJOR exit /b 0
set "JAVA_VER=!MAJOR!"
for /f "tokens=1 delims=." %%a in ("!MAJOR!") do set "MAJOR=%%a"
if "!MAJOR!"=="1" exit /b 0
if !MAJOR! LSS 25 exit /b 0
set "JAVA=%~1"
exit /b 0

:take
set "T=%~1"
set "T=!T:"=!"
if "!T!"=="" exit /b 0
set "CH=!T:~0,1!"
if "!CH!" LSS "0" exit /b 0
if "!CH!" GTR "9" exit /b 0
set "MAJOR=!T!"
exit /b 0

:deep
if not exist "%~1" exit /b 0
for /f "delims=" %%f in ('dir /s /b "%~1\java.exe" 2^>nul') do (
  if not defined JAVA call :check "%%f"
)
exit /b 0
