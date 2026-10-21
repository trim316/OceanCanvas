@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..\..") do set "PLANNER_ROOT=%%~fI"
pushd "%PLANNER_ROOT%"
where py >nul 2>nul
if %errorlevel%==0 (
  py "%SCRIPT_DIR%upgrade_major_regions_live.py"
) else (
  python "%SCRIPT_DIR%upgrade_major_regions_live.py"
)
set "RC=%errorlevel%"
popd
echo.
if not "%RC%"=="0" (
  echo Upgrade failed with exit code %RC%.
) else (
  echo Upgrade complete. Close the currently running planner window/server and run Run_Island_Auto.cmd once more.
)
pause
exit /b %RC%
