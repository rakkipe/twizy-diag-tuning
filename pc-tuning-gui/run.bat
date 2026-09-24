@echo off
title Twizy Pitservice - Tuning GUI
cd /d "%~dp0"
python -m pip install --quiet pyserial 2>nul
python Twizy_Pitservice_GUI.py 2>nul
if errorlevel 1 py Twizy_Pitservice_GUI.py
pause
