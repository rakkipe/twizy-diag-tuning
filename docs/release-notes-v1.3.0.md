## OpenRLink 1.3.0 (Android)

Diagnostics & tuning for the Renault Twizy 80 (SEVCON Gen4) from your phone, via a
vLinker / ELM327 USB-CAN adapter over USB-OTG.

### In this build
- **Tuning profiles**: STOCK, ECO, STAD (city), SNELWEG (highway), RACE-LITE — with a confirm dialog
- **Live meters** (OVMS 0x4600/0x4602): battery/cap voltage, motor current/voltage, power, torque, output frequency
- **ECU scan** (UDS/KWP) for UCH / BMS / charger / cluster, read-only
- **DTC → text** using *your own* DDT4All `ecu.zip` (see README)

### Install
1. Download **`OpenRLink-1.3.0-release.apk`** below.
2. On the phone, allow installing from this source (unknown sources) and tap the file.
3. Connect the vLinker with an OTG cable → **Verbinden → Verbind USB**.

Signed with the project's release key. Requires Android 8+ (minSdk 26).

### ECU database (optional)
The app works without it (shows raw DTC codes). For plain-text fault descriptions, supply
your own DDT4All `ecu.zip` via the **ECU tab → Kies ecu.zip**. The proprietary Renault
database is **not** included and not distributed here.

### ⚠️ Disclaimer
This writes tuning to the motor controller. Use **at your own risk**, **stationary and in N**.
Aggressive profiles can damage the drivetrain and may make the vehicle illegal for public
roads / void insurance. Intended for closed-course use. Not affiliated with Renault or SEVCON.
