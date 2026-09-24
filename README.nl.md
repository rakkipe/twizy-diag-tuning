# Twizy Diagnose- & Tuning-toolkit

![License: MIT](https://img.shields.io/badge/license-MIT-green)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-blue)
![Vehicle](https://img.shields.io/badge/vehicle-Renault%20Twizy%2080-orange)
![Interface](https://img.shields.io/badge/interface-vLinker%20%2F%20ELM327-lightgrey)
[![Download APK](https://img.shields.io/badge/download-latest%20Android%20APK-brightgreen)](https://github.com/rakkipe/twizy-diag-tuning/releases/latest)

Open-source diagnose en tuning voor de **Renault Twizy 80 (SEVCON Gen4)**, via een
vLinker / ELM327 USB-CAN-adapter. Twee apps met dezelfde geverifieerde logica:

- **Twizy Pitservice PRO** — een Windows-werkplaatstool (Python/Tkinter)
- **OpenRLink** — een Android-app (Kotlin/Compose) voor telefoon + OTG-adapter

**Talen:** [🇬🇧 English](README.md) · [🇳🇱 Nederlands](README.nl.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md)

---

## ⚠️ Disclaimer — lees dit eerst

Deze software schrijft tuning-parameters (snelheid, koppel, stroom, regen) rechtstreeks
naar de motorcontroller. Agressieve profielen duwen de aandrijflijn **voorbij de
ontwerpgrenzen** en kunnen motor, tandwielkast, koppeling of accu beschadigen en het
bereik sterk verlagen.

- Gebruik volledig **op eigen risico**. De makers aanvaarden **geen aansprakelijkheid**
  voor schade, letsel, verlies van garantie of gevolgen voor toelating/verzekering.
- Tuning die snelheid of vermogen verhoogt kan het voertuig **niet-toegelaten op de
  openbare weg** maken en de verzekering laten vervallen. Bedoeld voor **afgesloten
  terrein / circuit / werkbank**.
- Alleen schrijven met het voertuig **stilstaand en in N (niet GO)**, contact aan.
- Dit is **geen** officiële Renault-tool en niet verbonden met Renault of SEVCON.

Ga je hiermee niet akkoord, gebruik de software dan niet.

---

## Screenshots

**Twizy Pitservice PRO (Windows)** — Live-telemetrie-tab:

![Twizy Pitservice PRO](docs/screenshots/pc-pitservice-pro.png)

**OpenRLink (Android)** — Tuning-profielen-tab:

<img src="docs/screenshots/android-openrlink.png" width="320" alt="OpenRLink Android">

## Wat het is

Beide apps praten met de **SEVCON Gen4** via **CANopen SDO** (tuning + live-telemetrie) en
met de overige ECU's via **UDS/KWP over ISO-TP** (foutcodes lezen). De register-maps en
tuning-berekeningen zijn een getrouwe port van het open-source
[OVMS](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) /
[dexterbg Twizy-Cfg](https://github.com/dexterbg/Twizy-Cfg) werk — geverifieerd, niet verzonnen.

## Mappenstructuur

```
android/         OpenRLink — Android-app (Kotlin/Compose)
pc-tuning-gui/   Twizy Pitservice PRO — Windows Python-app
windows/         oudere/experimentele Python-client (ter referentie)
docs/            notities
```

## Functies

- **Tuning-profielen**: STOCK, ECO, STAD, SNELWEG, RACE-LITE (en 110 Nm / RACE op de pc)
- **Live-meters** (OVMS 0x4600/0x4602): accu-/cap-spanning, motorstroom/-spanning, vermogen, koppel, uitgangsfrequentie
- **Diagnose**: SEVCON-status + actieve faults (CANopen), multi-ECU DTC-scan (UDS/KWP)
- **Optionele DTC-tekst**: vertaalt foutcodes met jóuw eigen DDT4All-database (zie onder)
- PC-extra's: veilige backup-vóór-schrijven, werkbon-export, DDT4All-launcher

## Vereisten

- Renault Twizy 80 met **SEVCON Gen4**-controller
- **vLinker FS / ELM327** USB-CAN-adapter (STN aanbevolen), 500 kbps
- PC-app: Windows + Python 3.8+ en `pyserial`
- Android-app: Android 8+ (minSdk 26), USB-OTG-kabel

## Snelstart — PC (Twizy Pitservice PRO)

```
cd pc-tuning-gui
python -m pip install pyserial
python Twizy_Pitservice_GUI.py      # of dubbelklik run.bat
```
Kies adapter + COM-poort → **Verbinden**. Tabs: Live, Diagnose, ECU-scan, Tuning, Werkbon,
DDT4All, Recovery.

## Snelstart — Android (OpenRLink)

Installeer de ondertekende APK (zelf bouwen met Android Studio / Gradle, of een release-APK
gebruiken). Sluit de vLinker aan met een **OTG-kabel** → **Verbinden → Verbind USB**. Tabs:
Twizy, Tuning, Live, ECU, Terminal.

> Bouwen vereist JDK 17 + Android SDK. Een release-build vraagt je eigen signeer-keystore
> (nooit committen — staat in `.gitignore`).

## ECU-database (ecu.zip) — optioneel, jij levert 'm

De apps werken **zonder** database — de ECU-scan toont dan de ruwe DTC-codes. Wil je ook de
**klachtteksten** zien, dan lezen de apps een DDT4All-`ecu.zip` die **jij aanlevert vanuit
je eigen DDT4All-installatie**.

> ⚖️ De Renault ECU-database (DDT2000) is **proprietary**. Die zit **niet** in deze repo en
> er wordt hier **geen** download aangeboden. Verkrijg hem alleen via legitieme weg en houd
> hem voor persoonlijk gebruik. `ecu.zip` staat bewust in `.gitignore`.

**Waar plaats je 'm**
- **PC**: zet `ecu.zip` waar de app kijkt (standaard `D:\downloads\ecu.zip`, of naast
  `Twizy_Pitservice_GUI.py`). De ECU-scan-tab toont of hij gevonden is.
- **Android**: ECU-tab → **Kies ecu.zip** (systeem-bestandskiezer) — de app kopieert hem
  naar zijn eigen opslag en leest hem daar.

De app *leest* je bestand alleen at runtime; hij bundelt of verspreidt de database nooit.

## Veiligheid samengevat

Stilstaand, in N, contact aan. Maak eerst een backup (PC). Begin mild (ECO/STAD), bouw op.
Let op de Live-meters — zakt de spanning of loopt de temperatuur op, dan knijpt de SEVCON
zelf dicht: dat is bescherming, geen storing. STOCK zet alles terug naar fabriek.

## Met dank aan

- [OVMS](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) en
  [dexterbg Twizy-Cfg](https://github.com/dexterbg/Twizy-Cfg) — SEVCON register-maps & tuning-math
- [DDT4All](https://github.com/cedricp/ddt4all) — ECU-database-formaat (at runtime gelezen; niet meegeleverd)

## Licentie

MIT — zie `LICENSE`. Geleverd "as is", zonder enige garantie. De MIT-licentie dekt alleen
de eigen code van dit project, niet een database van derden die je ermee gebruikt.

