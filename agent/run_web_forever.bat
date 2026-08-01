@echo off
cd /d "%~dp0"
:loop
".venv\Scripts\python.exe" -u web_ui.py >> logs\web_ui_supervisor.log 2>&1
echo [%date% %time%] web_ui.py exited, restarting in 5s... >> logs\web_ui_supervisor.log
timeout /t 5 /nobreak >nul
goto loop
