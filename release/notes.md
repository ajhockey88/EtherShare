⚡ EtherShare 1.1.2: the speed test works again, and steadier speed numbers.

## ✨ What's new in 1.1.2
- ⚡ **Speed test fixed:** it could read 0.00 when its one server, in Sweden, was slow to answer: the test ran out of time before any data arrived. It now picks the nearest of several public test servers (Tele2 and Linode's, in North America, Europe and Asia), starts timing only once data is moving, and says what went wrong if a server doesn't answer
- 📈 **Steadier live speeds:** traffic comes in bursts, so the download and upload numbers jumped between 0 and full speed every second. They now show the average of the last 3 seconds. The graph still shows every second

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
- **1.1.2:** the speed test works again (nearest server), and steadier live speed numbers
- **1.1.1:** game streams lose far fewer packets (bigger buffers for UDP in the PC program)
- **1.1:** a much faster cable (8 adb connections at once), and the connect screen scrolls
- **1.0.1:** the speed test works
- **1.0:** first release: the PC's wired internet over USB, live speeds, a speed test and the PC program for Windows, Mac and Linux

## 📲 Install
Already have it? In EtherShare, open Settings > **Check for updates**, or download `EtherShare.apk` below and open it on the Thor. The PC program is the same as 1.1.1; if you're on an older one, download the new one below too.

New to EtherShare?
1. Download `EtherShare.apk` below, open it on the Thor to install, and turn on **USB debugging** (Settings > About > tap Build number 7 times, then Developer options > USB debugging).
2. On your PC, download the file for it below: `EtherShare-Windows.exe`, `EtherShare-Mac-AppleSilicon.zip`, `EtherShare-Mac-Intel.zip` or `EtherShare-Linux.zip`, and run it.
3. Plug the Thor into the PC, tap **Allow** on the USB debugging prompt and **OK** on the VPN prompt. Leave the PC window open while you use it.
