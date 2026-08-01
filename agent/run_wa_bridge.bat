@echo off
REM Launch the WhatsApp Web bridge (PLAN_V2 Task 5.2). Requires Node.js LTS installed
REM and WA_BRIDGE_KEY set in agent\.env.

echo.
echo ========================================
echo MyPersonalAgent WhatsApp Bridge Launcher
echo ========================================
echo.

cd /d "%~dp0services\wa-bridge"

REM Pull WA_BRIDGE_KEY (and optional WA_BRIDGE_PORT) out of agent\.env into this shell.
for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0.env") do (
    if "%%A"=="WA_BRIDGE_KEY" set "WA_BRIDGE_KEY=%%B"
    if "%%A"=="WA_BRIDGE_PORT" set "WA_BRIDGE_PORT=%%B"
)

if "%WA_BRIDGE_KEY%"=="" (
    echo WA_BRIDGE_KEY is not set in agent\.env.
    echo Generate one and add it, e.g.:
    echo   python -c "import secrets;print(secrets.token_urlsafe(32))"
    echo Then add a line to agent\.env:  WA_BRIDGE_KEY=your-generated-value
    pause
    exit /b 1
)

if not exist node_modules (
    echo Installing Node dependencies (first run only)...
    call npm install
)

echo.
echo Starting WhatsApp bridge on http://127.0.0.1:8600 ...
echo On first run, a QR code will print below - scan it with WhatsApp
echo (Settings ^> Linked Devices ^> Link a Device) to pair.
echo.
node server.js

pause
