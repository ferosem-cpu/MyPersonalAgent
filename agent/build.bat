@echo off
setlocal
cd /d "%~dp0"
python -m pip install -r requirements.txt
python -m PyInstaller --onedir --name agent agent.py --add-data "config.json;." --add-data ".env.example;."
echo Build complete: agent\dist\agent
