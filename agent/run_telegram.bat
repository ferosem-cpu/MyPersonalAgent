@echo off
REM Launch MyPersonalAgent Telegram bot

echo.
echo ========================================
echo MyPersonalAgent Telegram Bot Launcher
echo ========================================
echo.

REM Activate virtual environment
call .\.venv\Scripts\activate.bat

echo.
echo Starting Telegram bot (Ctrl+C to stop)...
echo.
python run_telegram.py

pause
