@echo off
chcp 65001 >nul
title Snake NET - брандмауэр

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo   Нужны права администратора.
  echo   Нажми на файл правой кнопкой -^> "Запуск от имени администратора".
  echo.
  pause
  exit /b 1
)

echo   Открываю порты для Snake NET...
netsh advfirewall firewall delete rule name="Snake NET TCP" >nul 2>&1
netsh advfirewall firewall delete rule name="Snake NET UDP" >nul 2>&1
netsh advfirewall firewall add rule name="Snake NET TCP" dir=in action=allow protocol=TCP localport=45800 >nul
netsh advfirewall firewall add rule name="Snake NET UDP" dir=in action=allow protocol=UDP localport=45777 >nul
netsh advfirewall firewall add rule name="Snake NET UDP out" dir=out action=allow protocol=UDP localport=45777 >nul

echo.
echo   Готово: 45800/TCP и 45777/UDP открыты для входящих.
echo   Если в настройках игры выбран другой порт, открой его так же
echo   или просто нажми "Разрешить" в окне брандмауэра Windows.
echo.
pause
