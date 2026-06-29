@echo off
setlocal
set "APP_EXE=%~dp0..\..\ClawAgent.exe"
if exist "%APP_EXE%" (
  "%APP_EXE%" --cli %*
  exit /b %ERRORLEVEL%
)
if exist "%~dp0clawagent.mjs" (
  node "%~dp0clawagent.mjs" %*
  exit /b %ERRORLEVEL%
)
echo Cannot find ClawAgent.exe or clawagent.mjs. Please reinstall ClawAgent.
exit /b 1
