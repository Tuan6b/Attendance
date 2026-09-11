@echo off
echo Dang tim va tat tien trinh Face AI tren cong 8002...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8002" ^| findstr "LISTENING"') do (
    echo Ket thuc tien trinh PID %%a...
    taskkill /F /PID %%a
)
echo Hoan tat!
pause
