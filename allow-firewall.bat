@echo off
chcp 65001 >nul
title Snake NET - firewall

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo   Administrator rights are required.
  echo   Right-click the file and choose "Run as administrator".
  echo.
  pause
  exit /b 1
)

echo   Opening ports for Snake NET...
netsh advfirewall firewall delete rule name="Snake NET TCP" >nul 2>&1
netsh advfirewall firewall delete rule name="Snake NET UDP" >nul 2>&1
netsh advfirewall firewall add rule name="Snake NET TCP" dir=in action=allow protocol=TCP localport=45800 >nul
netsh advfirewall firewall add rule name="Snake NET UDP" dir=in action=allow protocol=UDP localport=45777 >nul
netsh advfirewall firewall add rule name="Snake NET UDP out" dir=out action=allow protocol=UDP localport=45777 >nul

echo.
echo   Done: 45800/TCP and 45777/UDP are open for incoming traffic.
echo   If the game uses another port, open it the same way,
echo   or simply press "Allow" in the Windows firewall dialog.
echo.
pause
