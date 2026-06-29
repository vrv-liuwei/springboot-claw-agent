!include WinMessages.nsh

!macro customInstall
  DetailPrint "Registering ClawAgent CLI in user PATH"
  ExecWait 'powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$INSTDIR\resources\cli\register-path.ps1" "$INSTDIR\resources\cli"'
  SendMessage ${HWND_BROADCAST} ${WM_SETTINGCHANGE} 0 "STR:Environment" /TIMEOUT=5000
!macroend

!macro customUnInstall
  DetailPrint "Removing ClawAgent CLI from user PATH"
  ExecWait 'powershell -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$INSTDIR\resources\cli\unregister-path.ps1" "$INSTDIR\resources\cli"'
  SendMessage ${HWND_BROADCAST} ${WM_SETTINGCHANGE} 0 "STR:Environment" /TIMEOUT=5000
!macroend
