@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title Snake NET - сборка
cd /d "%~dp0"

set "JDK="
set "MAJOR="

if defined JAVA_HOME call :check "%JAVA_HOME%\bin\javac.exe"

if not defined JDK (
  for /f "delims=" %%j in ('where javac 2^>nul') do (
    if not defined JDK call :check "%%j"
  )
)

if not defined JDK call :deep "C:\Program Files\Java"
if not defined JDK call :deep "C:\Program Files\Eclipse Adoptium"
if not defined JDK call :deep "C:\Program Files\Microsoft"
if not defined JDK call :deep "C:\Program Files (x86)\Java"
if not defined JDK call :deep "%LOCALAPPDATA%\Programs\Eclipse Adoptium"
if not defined JDK call :deep "%USERPROFILE%\.jdks"
if not defined JDK call :deep "%APPDATA%\.minecraft\runtime"
if not defined JDK call :deep "%APPDATA%\KLauncher\runtime"

if not defined JDK (
  echo.
  echo   JDK 17+ не найден. Для сборки нужен JDK, а не только JRE.
  echo   Скачать: https://adoptium.net/temurin/releases/?version=17
  echo.
  pause
  exit /b 1
)

echo   JDK !MAJOR! найден.
echo.
echo   [1/4] Компилирую движок NetCore...
if exist "out\engine" rmdir /s /q "out\engine"
mkdir "out\engine" 2>nul
> "%TEMP%\netcore_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b engine\src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)
"%JDK%javac.exe" -encoding UTF-8 -d "out\engine" @"%TEMP%\netcore_sources.txt"
if errorlevel 1 goto :fail

echo   [2/4] Компилирую змейку...
if exist "out\game" rmdir /s /q "out\game"
mkdir "out\game" 2>nul
> "%TEMP%\snake_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b game\src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)
"%JDK%javac.exe" -encoding UTF-8 -cp "out\engine" -d "out\game" @"%TEMP%\snake_sources.txt"
if errorlevel 1 goto :fail

echo   [3/4] Собираю NetCore.jar...
del /q "NetCore.jar" 2>nul
"%JDK%jar.exe" --create --file "NetCore.jar" -C "out\engine" .
if errorlevel 1 goto :fail

echo   [4/4] Собираю SnakeNET.jar...
del /q "SnakeNET.jar" 2>nul
"%JDK%jar.exe" --create --file "SnakeNET.jar" --manifest "manifest.txt" -C "out\game" .
if errorlevel 1 goto :fail

echo.
echo   Готово: NetCore.jar (движок) + SnakeNET.jar (игра)
echo   Запуск: run.bat
echo.
pause
exit /b 0

:fail
echo.
echo   Сборка не удалась.
pause
exit /b 1

:check
if not exist "%~1" exit /b 0
set "MAJOR="
set "FIRST=0"
"%~1" -version > "%TEMP%\snakenet_javac.txt" 2>&1
for /f "usebackq tokens=1,2,3,4" %%a in ("%TEMP%\snakenet_javac.txt") do (
  if !FIRST!==0 (
    call :take "%%a"
    if not defined MAJOR call :take "%%b"
    if not defined MAJOR call :take "%%c"
    if not defined MAJOR call :take "%%d"
    set "FIRST=1"
  )
)
if not defined MAJOR exit /b 0
set "VER=!MAJOR!"
for /f "tokens=1 delims=." %%a in ("!MAJOR!") do set "MAJOR=%%a"
if "!MAJOR!"=="1" exit /b 0
if !MAJOR! LSS 17 exit /b 0
set "JDK=%~dp1"
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
for /f "delims=" %%f in ('dir /s /b "%~1\javac.exe" 2^>nul') do (
  if not defined JDK call :check "%%f"
)
exit /b 0
