🚀 EtherShare 1.1: a much faster cable.

## ✨ What's new in 1.1
- 🚀 **Much faster:** EtherShare now sends your traffic over 8 adb connections at once instead of 1. adb waits for each piece to arrive before sending the next, which held one connection to about 90 Mbps; with 8, those waits overlap. The packets are put back in order at each end, so even a single download gets the full speed
- 📜 **The connect screen scrolls:** the speed test card no longer falls off the bottom of the screen
- 💻 **Update the PC program too:** the speed-up needs both. The new app still works with the old PC program (at the old speed), and the new PC program still works with the old app

---

## 🔌 What EtherShare does
EtherShare gives the AYN Thor your PC's wired internet over USB, through a small program you run on the PC.
- 🔌 **Plug in and go:** the PC program spots the Thor when the cable goes in and connects it by itself. Unplug and the Thor goes back to Wi-Fi
- 🌙 **Works in the background:** close EtherShare and the connection keeps going for every app until you unplug or tap Disconnect in the notification
- 📈 **Live speeds:** download, upload, data used and time connected on the bottom screen, and a big two-minute speed graph on the top screen
- ⚡ **Speed test:** ping, download and upload, with your last cable and Wi-Fi results side by side
- 🖥️ **Reach your PC:** while connected, the PC is at 10.88.0.1 for Moonlight, media servers and anything else running on it
- 🌐 **DNS your way:** use the PC's own DNS (Pi-hole and work VPNs included), or Cloudflare, Google or Quad9
- 💻 **Windows, Mac and Linux:** one file to run on the PC, which downloads adb by itself if it needs it
- 🎮 **Gamepad:** A connects, X runs the speed test, Y opens Settings, B closes
- 📳 **Haptics:** a feel and strength for connecting, disconnecting, menu buttons and the speed test
- 🔄 **Updates:** check, see what's new, and install from Settings

## 📜 Release history
- **1.1:** a much faster cable (8 adb connections at once), and the connect screen scrolls
- **1.0.1:** the speed test works
- **1.0:** first release: the PC's wired internet over USB, live speeds, a speed test and the PC program for Windows, Mac and Linux

## 📲 Install
Already have it? In EtherShare, open Settings > **Check for updates**, or download `EtherShare.apk` below and open it on the Thor. **Download the new PC program too** (the file for your PC below) and use it in place of the old one: the speed-up needs both.

New to EtherShare?
1. Download `EtherShare.apk` below, open it on the Thor to install, and turn on **USB debugging** (Settings > About > tap Build number 7 times, then Developer options > USB debugging).
2. On your PC, download the file for it below: `EtherShare-Windows.exe`, `EtherShare-Mac-AppleSilicon.zip`, `EtherShare-Mac-Intel.zip` or `EtherShare-Linux.zip`, and run it.
3. Plug the Thor into the PC, tap **Allow** on the USB debugging prompt and **OK** on the VPN prompt. Leave the PC window open while you use it.
