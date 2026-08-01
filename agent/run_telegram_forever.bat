@echo off
cd /d "%~dp0"
:loop
".venv\Scripts\python.exe" -u run_telegram.py >> logs\telegram_supervisor.log 2>&1
echo [%date% %time%] run_telegram.py exited, restarting in 5s... >> logs\telegram_supervisor.log
timeout /t 5 /nobreak >nul
goto loop
