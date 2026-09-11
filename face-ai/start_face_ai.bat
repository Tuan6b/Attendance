@echo off
cd /d "%~dp0"
echo ===================================================
echo   Khoi dong Face AI Service (FastAPI)
echo   Port: 8002 ^| Host: 0.0.0.0
echo ===================================================

if not exist ".venv\Scripts\python.exe" (
    echo [ERROR] Khong tim thay moi truong ao .venv!
    echo Vui long tao moi truong ao bang: python -m venv .venv
    pause
    exit /b 1
)

set PORT=8002
set HOST=0.0.0.0
set RELOAD=false

echo Dang khoi chay ung dung...
".venv\Scripts\python.exe" run.py
pause
