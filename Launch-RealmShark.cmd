@echo off
setlocal
cd /d "%~dp0"
set "REALMSHARK_JAVA=javaw.exe"
for /d %%J in ("%~dp0.tools\jdk-*") do if exist "%%~fJ\bin\javaw.exe" set "REALMSHARK_JAVA=%%~fJ\bin\javaw.exe"
if not exist "build\libs\RealmShark-v1.2.3.jar" (
    echo Build the application first with gradlew.bat shadowJar using JDK 17.
    pause
    exit /b 1
)
start "" "%REALMSHARK_JAVA%" -jar "build\libs\RealmShark-v1.2.3.jar" %*
