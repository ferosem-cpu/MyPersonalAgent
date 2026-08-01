@echo off
REM Launch MyPersonalAgent REST API server

echo.
echo ========================================
echo MyPersonalAgent API Launcher
echo ========================================
echo.

REM Activate virtual environment
call .\.venv\Scripts\activate.bat

REM Check for FastAPI
python -c "import fastapi" >nul 2>&1
if errorlevel 1 (
    echo Installing FastAPI/uvicorn...
    pip install fastapi "uvicorn[standard]" pydantic
)

echo.
echo Starting API server...
echo.
echo Open your browser and go to: http://localhost:8500/docs
echo.
python run_api.py

pause
