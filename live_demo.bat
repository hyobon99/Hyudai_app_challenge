@echo off
rem Car-ind CHAT live demo: shows the real road situation and both emulators' app screens side by side.
rem Usage: live_demo.bat [merge|yield]   (needs both Pleos emulators running)
cd /d %~dp0
set SC=%1
if "%SC%"=="" set SC=merge
python tools\scenarioun_scenario.py %SC% --live --loop
pause
