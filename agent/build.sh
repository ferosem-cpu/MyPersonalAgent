#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
python3 -m pip install -r requirements.txt
python3 -m PyInstaller --onedir --name agent agent.py --add-data "config.json:." --add-data ".env.example:."
echo "Build complete: agent/dist/agent"
