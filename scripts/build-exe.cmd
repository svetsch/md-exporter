@echo off
rem Builds the Windows executable. Arguments are passed to build-exe.ps1, e.g.
rem   scripts\build-exe.cmd                  portable app folder + zip (no extra tools needed)
rem   scripts\build-exe.cmd -Type exe        setup .exe installer (needs WiX Toolset 3.x)
rem   scripts\build-exe.cmd -Type all -SkipTests
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-exe.ps1" %*
exit /b %ERRORLEVEL%
