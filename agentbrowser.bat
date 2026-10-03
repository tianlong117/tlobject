@echo off
chcp 65001 >nul
setlocal
rem agentbrowser.bat - start the "agent browser" (system Chrome/Edge + debug port)
rem   default: dedicated profile (shared with the agent's auto-launch; path must match cdpProfileDir default)
rem   daily:   disabled - Chrome/Edge 136+ block the debug port on the default profile path
rem agent config: cdpEndpoint="http://127.0.0.1:9222" cdpAutoLaunch="true"

if /i "%~1"=="daily" (
    echo [x] daily mode unavailable: Chrome/Edge 136+ disables the debug port on the default profile path.
    echo     Use the default mode and log in once inside the dedicated profile - the login persists.
    pause
    exit /b 1
)

set "EXE="
set "BROWSER=chrome"
rem 探测顺序与 browser_agent.py:_detect_browser 保持一致（先 Chrome 全部候选，再 Edge），
rem 否则混合安装下 bat 与自动拉起可能选中不同浏览器 → 同一 profile 被两个浏览器实例争用
if exist "%ProgramFiles%\Google\Chrome\Application\chrome.exe" set "EXE=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not defined EXE if exist "%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe" set "EXE=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not defined EXE if exist "%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe" set "EXE=%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"
if not defined EXE if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" (set "EXE=%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" & set "BROWSER=edge")
if not defined EXE if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" (set "EXE=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" & set "BROWSER=edge")
if not defined EXE if exist "%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe" (set "EXE=%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe" & set "BROWSER=edge")
if not defined EXE (
    echo [x] 未找到系统 Chrome/Edge
    pause
    exit /b 1
)

set "PORT=9222"
set "PROFILE=%~dp0data\browser_agent_profile"

echo [i] 浏览器: %BROWSER%
echo [i] profile: %PROFILE%
echo [i] 调试端口: %PORT%   agent 侧: cdpEndpoint="http://127.0.0.1:%PORT%" cdpAutoLaunch="true"
start "" "%EXE%" --remote-debugging-port=%PORT% --user-data-dir="%PROFILE%" --no-first-run --no-default-browser-check

set "READY="
for /l %%i in (1,1,8) do (
    if not defined READY (
        curl -sf -m 1 -o nul "http://127.0.0.1:%PORT%/json/version" && set "READY=1"
        if not defined READY ping -n 2 127.0.0.1 >nul 2>&1
    )
)
if not defined READY goto fail
echo [i] OK: debug endpoint ready at http://127.0.0.1:%PORT%
exit /b 0

:fail
echo [x] Debug port %PORT% did not open. Likely causes:
echo     - another app occupies port %PORT%
echo     - this profile already has a running instance (only one at a time)
echo     - using a default browser profile (136+ blocks debugging there)
pause
exit /b 1
