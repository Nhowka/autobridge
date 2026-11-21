# AutoBridge

🇧🇷 [Leia em português](README.pt-BR.md)

**Give your car's head unit internet from your phone – over the Wi-Fi link wireless Android Auto already uses.**
No phone hotspot, no extra SIM: while Android Auto is connected, the head unit's apps (maps, music, updates…) go online
through the phone's mobile data.

One small app (≈1.3 MB) for both sides. You install it **on the phone only** – the phone then installs and sets it up
on the head unit itself over a USB cable.

> Built and tested on a Jaecoo J7 (Desay SV head unit, Android 11) with a recent Android phone. Other Android head
> units that allow ADB should work too, but are untested.

## Download
Get `AutoBridge-<version>.apk` from **[Releases](../../releases)** and install it on your phone (allow "install unknown
apps" for your browser or file manager when asked).

## Set up the car over USB (once, and after each update)
1. **Turn on ADB on the head unit.** On Chery / Jaecoo / Omoda (Desay SV) units it's a switch in the engineering /
   debug menu; the head unit's USB data port then works in ADB (device) mode.
2. **Connect the phone so the phone is the USB host:** a USB-C OTG adapter on the phone plus a USB-A to USB-A cable to
   the car's port (or a USB-C cable if the car's port is USB-C). If the phone only starts charging, it isn't the host –
   use the OTG adapter.
3. Android asks to **open AutoBridge** for the USB device – tap OK (or open AutoBridge and tap **Set up car (USB)**,
   which appears while the car is connected). Allow USB access.
4. Tap **Set up car**. AutoBridge installs itself on the head unit (also on units where `pm install` is blocked),
   allows its VPN, makes it the car side and opens it. If the head unit asks *"Allow USB debugging?"*, tick
   *Always allow*.
5. **Turn ADB off again** on the head unit, so USB drives work again and nobody else can use ADB.

To update the car later: update AutoBridge on the phone, then repeat steps 1–5 – *Set up car* updates the head unit to
the phone's version.

## Use it
In the car, with wireless Android Auto connected, open AutoBridge on the phone and tap **Share internet**. Tick
*Start automatically when the car connects* to have it start by itself on every drive (Android asks for Bluetooth
access and to let AutoBridge run in the background). The phone's notification shows how much data the car used;
the head unit shows *Internet: from your phone*.

## How it works
- **Phone:** a SOCKS5 proxy on the phone's address on the car Wi-Fi (new password every session), sending traffic out
  over the mobile-data network. Every 20 s it offers itself to the car.
- **Car:** a VPN that sends the head unit's internet traffic (public addresses only – the car's own Wi-Fi devices stay
  local) to that proxy, using [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel). It stops about a minute
  after the phone stops offering.
- **USB set-up:** AutoBridge contains a small ADB client. It copies its own APK to the head unit and installs it through
  Android's PackageInstaller (a tiny helper run with `app_process`, source in `installer-dex/`), because some head
  units refuse `pm install`.

## Good to know
- **Data:** everything the head unit downloads counts against your phone's mobile data.
- **One VPN at a time:** Android allows only one VPN on the head unit; don't run another VPN app there while sharing.
- **No pairing, by design:** the car accepts an internet offer from any device on its own Wi-Fi. That Wi-Fi is
  password-protected and normally only your phone (Android Auto) is on it.
- Not affiliated with Google, Android Auto, Chery, Jaecoo, Omoda or Desay SV.

## Build
    ./gradlew assembleRelease
Requires the Android SDK (compileSdk 37) and NDK 28.2. Build output goes to `~/.gradle-builds/AutoBridge/`.
The native tunnel is prebuilt in `app/src/main/jniLibs/` (how: `app/src/main/jniLibs/README.md`).

## Credits
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) by hev (MIT), with lwIP, hev-task-system and
  libyaml – licences in `app/src/main/assets/licenses/`.
