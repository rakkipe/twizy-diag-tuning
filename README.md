# Twizy Diag & Tuning Toolkit

![License: MIT](https://img.shields.io/badge/license-MIT-green)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-blue)
![Vehicle](https://img.shields.io/badge/vehicle-Renault%20Twizy%2080-orange)
![Interface](https://img.shields.io/badge/interface-vLinker%20%2F%20ELM327-lightgrey)
[![Download APK](https://img.shields.io/badge/download-latest%20Android%20APK-brightgreen)](https://github.com/rakkipe/twizy-diag-tuning/releases/latest)

Open-source diagnostics and tuning for the **Renault Twizy 80 (SEVCON Gen4)**, over a
vLinker / ELM327 USB-CAN adapter. Two apps that share the same verified logic:

- **Twizy Pitservice PRO** — a Windows (Python/Tkinter) workshop tool
- **OpenRLink** — an Android app (Kotlin/Compose) for use with a phone + OTG adapter

**Languages:** [🇬🇧 English](README.md) · [🇳🇱 Nederlands](README.nl.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md)

---

## ⚠️ Disclaimer — read this first

This software writes tuning parameters (speed, torque, current, regen) directly to a
vehicle motor controller. Aggressive profiles push the drivetrain **beyond its design
limits** and can damage the motor, gearbox, clutch or battery, and drastically reduce
range.

- Use entirely **at your own risk**. The authors accept **no liability** for damage,
  injury, loss of warranty, or legal/insurance consequences.
- Tuning that raises speed or power may make the vehicle **illegal for public roads** and
  can void insurance. Intended for **closed-course / track / bench** use.
- Only write with the vehicle **stationary and in N (not GO)**, ignition on.
- This is **not** an official Renault tool and is not affiliated with Renault or SEVCON.

If you do not accept this, do not use the software.

---

## Screenshots

**Twizy Pitservice PRO (Windows)** — Live telemetry tab:

![Twizy Pitservice PRO](docs/screenshots/pc-pitservice-pro.png)

**OpenRLink (Android)** — Tuning profiles tab:

<img src="docs/screenshots/android-openrlink.png" width="320" alt="OpenRLink Android">

## What it is

Both apps talk to the Twizy's **SEVCON Gen4** controller via **CANopen SDO** (tuning +
live telemetry) and to the other ECUs via **UDS/KWP over ISO-TP** (fault-code reading).
The register maps and tuning math are a faithful port of the open-source
[OVMS](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) /
[dexterbg Twizy-Cfg](https://github.com/dexterbg/Twizy-Cfg) work — verified, not invented.

## Repository structure

```
android/         OpenRLink — Android app (Kotlin/Compose)
pc-tuning-gui/   Twizy Pitservice PRO — Windows Python app
windows/         earlier/experimental Python client (kept for reference)
docs/            notes
```

## Features

- **Tuning profiles**: STOCK, ECO, CITY/STAD, HIGHWAY/SNELWEG, RACE-LITE (and 110 Nm / RACE on PC)
- **Live meters** (OVMS 0x4600/0x4602): battery/cap voltage, motor current/voltage, power, torque, output frequency
- **Diagnostics**: SEVCON status + active faults (CANopen), multi-ECU DTC scan (UDS/KWP)
- **Optional DTC text**: translates fault codes using *your own* DDT4All ECU database (see below)
- PC extras: safe backup-before-write, work-order export, DDT4All launcher

## Requirements

- Renault Twizy 80 with a **SEVCON Gen4** controller
- **vLinker FS / ELM327** USB-CAN adapter (STN-based recommended), 500 kbps
- PC app: Windows + Python 3.8+ and `pyserial`
- Android app: Android 8+ (minSdk 26), USB-OTG cable

## Quick start — PC (Twizy Pitservice PRO)

```
cd pc-tuning-gui
python -m pip install pyserial
python Twizy_Pitservice_GUI.py      # or double-click run.bat
```
Pick the adapter + COM port → **Verbinden** (Connect). Tabs: Live, Diagnose, ECU-scan,
Tuning, Werkbon, DDT4All, Recovery.

## Quick start — Android (OpenRLink)

Install the signed APK (build it yourself with Android Studio / Gradle, or use a release
APK). Connect the vLinker with an **OTG cable** → **Verbinden → Verbind USB**. Tabs:
Twizy, Tuning, Live, ECU, Terminal.

> Building requires JDK 17 + Android SDK. A release build needs your own signing keystore
> (never commit it — it is git-ignored).

## ECU database (ecu.zip) — optional, you provide it

The apps work **without** any database — the ECU scan then shows raw DTC codes. To also
see the **plain-text fault descriptions**, the apps read a DDT4All-format `ecu.zip` that
**you supply from your own DDT4All installation**.

> ⚖️ The Renault ECU database (DDT2000) is **proprietary**. It is **not** included in this
> repository and no download is provided here. Obtain it only through legitimate means and
> keep it for personal use. `ecu.zip` is git-ignored on purpose.

**Where to put it**
- **PC**: place `ecu.zip` where the app looks (by default `D:\downloads\ecu.zip`, or next
  to `Twizy_Pitservice_GUI.py`). The ECU-scan tab shows whether it was found.
- **Android**: ECU tab → **Kies ecu.zip** (system file picker) — the app copies it into
  its own storage and reads it there.

The app only *reads* your file at runtime; it never bundles or redistributes the database.

## Safety recap

Stationary, in N, ignition on. Make a backup first (PC). Start mild (ECO/CITY), build up.
Watch the Live meters — if voltage sags or temperature climbs, the SEVCON will cut back:
that is protection, not a fault. STOCK restores factory values.

## Credits

- [OVMS](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) and
  [dexterbg Twizy-Cfg](https://github.com/dexterbg/Twizy-Cfg) — SEVCON register maps & tuning math
- [DDT4All](https://github.com/cedricp/ddt4all) — ECU database format (read at runtime; not included)

## License

MIT — see `LICENSE`. Provided "as is", without warranty of any kind. The MIT license
covers this project's own code only, not any third-party database you may use with it.
