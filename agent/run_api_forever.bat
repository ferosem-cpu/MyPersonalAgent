@echo off
cd /d "%~dp0"
:loop
".venv\Scripts\python.exe" -u run_api.py >> logs\api_supervisor.log 2>&1
echo [%date% %time%] run_api.py exited, restarting in 5s... >> logs\api_supervisor.log
timeout /t 5 /nobreak >nul
goto loop
