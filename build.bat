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

echo   Компилирую на JDK !MAJOR! ...
if exist "out\classes" rmdir /s /q "out\classes"
mkdir "out\classes" 2>nul

> "%TEMP%\snakenet_sources.txt" (
  for /f "delims=" %%f in ('dir /s /b src\main\java\*.java 2^>nul') do (
    set "F=%%f"
    echo "!F:\=/!"
  )
)

"%JDK%javac.exe" -encoding UTF-8 -d "out\classes" @"%TEMP%\snakenet_sources.txt"
if errorlevel 1 (
  echo.
  echo   Ошибка компиляции.
  pause
  exit /b 1
)

"%JDK%jar.exe" --create --file "SnakeNET.jar" --main-class snakenet.Main -C "out\classes" .
if errorlevel 1 (
  echo.
  echo   Не удалось собрать SnakeNET.jar
  pause
  exit /b 1
)

echo.
echo   Готово: SnakeNET.jar
echo   Теперь запускай run.bat
echo.
pause
exit /b 0

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
