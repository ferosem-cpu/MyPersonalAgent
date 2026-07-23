@echo off
REM Setup script for MyPersonalAgent
echo.
echo ========================================
echo MyPersonalAgent Setup
echo ========================================
echo.

REM Check Python installation
echo Checking Python installation...
python --version
if errorlevel 1 (
    echo ERROR: Python not found in PATH
    echo Please ensure Python was installed and "Add to PATH" was selected
    pause
    exit /b 1
)

echo.
echo Creating virtual environment...
python -m venv .venv

echo.
echo Activating virtual environment...
call .\.venv\Scripts\activate.bat

echo.
echo Installing dependencies...
python -m pip install --upgrade pip
python -m pip install -r requirements.txt

echo.
echo Setting up .env file...
if not exist .env (
    copy .env.example .env
    echo .env created. Please edit it with your ANTHROPIC_API_KEY
)

echo.
echo ========================================
echo Setup complete!
echo ========================================
echo.
echo Next steps:
echo 1. Edit agent\.env and add your ANTHROPIC_API_KEY
echo 2. Run: python agent.py
echo.
echo To open the tracker, visit: ..\tracker\index.html
echo.
pause
