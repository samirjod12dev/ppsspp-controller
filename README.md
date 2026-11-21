# Simple PSP Controller for PPSSPP

Android phone = PSP controller. Windows app = receiver that presses PPSSPP's default keys.

## Build (no local tools)
Push this repo to GitHub -> Actions -> run "Android" and "Windows" -> download artifacts
(PSPController-apk, PSPReceiver-exe).

## Use
1. Run PSPReceiver.exe (allow Private network in the firewall prompt).
2. Wi-Fi: phone + PC on same network, type the IP shown in the receiver, tap Connect via Wi-Fi.
   Bluetooth: pair phone with PC in Windows/Android settings first, then Connect via Bluetooth and pick the PC.
3. Click the PPSSPP window so it has focus, play. Back button on phone = disconnect.
