@echo off
REM Launch MyPersonalAgent Web UI

echo.
echo ========================================
echo MyPersonalAgent Web UI Launcher
echo ========================================
echo.

REM Activate virtual environment
call .\.venv\Scripts\activate.bat

REM Check for Flask
python -c "import flask" >nul 2>&1
if errorlevel 1 (
    echo Installing Flask...
    pip install flask
)

REM Launch web UI
echo.
echo Starting web server...
echo.
echo Open your browser and go to: http://localhost:5000
echo.
python web_ui.py

pause
