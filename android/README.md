# OpenRLink

Open-source Android diagnose-app voor Renault-voertuigen over een ELM327/STN-adapter
(getarget op de **vLinker FS2**). Repliceert de *functies* van Renault CLIP — **niet** de
gesloten propriëtaire CLIP-database. Gericht op de **Twizy**.

## Wat de app doet

- **OBD-II generiek**: DTC's lezen/wissen (mode 03/07/0A/04), live data (mode 01).
- **Raw UDS (ISO 14229) over ISO-TP**: services 0x10 / 0x22 / 0x2E / 0x19 / 0x14 / 0x31,
  met NRC-decodering. De ELM doet de ISO-TP framing (`ATCAF1`).
- **Twizy quick-actions**: vooraf gedefinieerde UDS-leesacties per ECU (PEB, LBC/BMS, BCB, TDB).
- **Terminal**: vrije AT/ST/hex-commando's voor handmatig sniffen en testen.
- **Demo-modus**: volledige UI zonder hardware (zoals AndrOBD demo-mode).

## Bouwen

1. Open de map in **Android Studio (Koala 2024.1.1+)**. Android Studio genereert de Gradle-wrapper.
   - CLI-alternatief: `gradle wrapper --gradle-version 8.9` daarna `./gradlew assembleDebug`.
2. SDK: `compileSdk 34`, `minSdk 26`. Kotlin 2.0.21, AGP 8.5.2, Compose BOM 2024.09.03.
3. APK: `app/build/outputs/apk/debug/app-debug.apk` → installeren op het toestel.

> Versies staan expliciet in `build.gradle.kts` zodat je ze in één plek kunt bumpen.

## Hardware-setup

1. **vLinker FS2** in de OBD/diagnose-poort; pair hem in Android-instellingen (Bluetooth).
2. App → tab **Verbinden** → kies de adapter → **Verbind**.
3. Init-sequentie (in `Elm327.initObd`): `ATZ ATE0 ATL0 ATS0 ATH1 ATSP6` (CAN 11-bit 500k).

## !! Te verifiëren: CAN-ID's !!

De ECU request/response-ID's in `renault/RenaultEcu.kt` zijn **startpunten, geen garantie**.
Verifieer ze vóór gebruik:

- **PEB** tx `75A` / rx `762` — gebaseerd op de besproken node 0x75A; rx bevestigen.
- **LBC/BMS** tx `79B` / rx `7BB` — typische Renault-EV-range; bevestigen.
- **BCB** tx `792` / rx `793`, **TDB** tx `743` / rx `763` — bevestigen.

Verificatiebronnen:
1. **DDT4All ECU-XML** (de `.xml`/`.dbc`-bestanden bevatten de exacte diag-adressen).
2. **Live sniff** met je M5StickC Plus2 + CAN-Unit, of via de Terminal-tab:
   `ATMA` (monitor all) om actieve ID's te zien, dan `ATSH<id>`/`ATCRA<id>` testen.

Pas de adressen in die ene tabel aan; de rest van de app volgt automatisch.

## Architectuur (modulair)

```
transport/   Transport-interface + BluetoothSPP / Demo  (USB-OTG = later toe te voegen)
elm/         ELM327/STN command-engine: init, CAN-headers, ISO-TP config
obd/         generieke OBD-II (PID-decoders, DTC-decoder, ObdService)
uds/         UdsClient: ISO-14229 services + NRC-tekst
renault/     ECU-adrestabel + Twizy quick-actions
ui/          AppViewModel (coroutines, IO-dispatcher) + Compose-schermen
```

Een nieuwe PID = één regel in `obd/Pid.kt`. Een nieuwe Twizy-actie = één item in
`renault/TwizyActions.kt`. Een nieuwe ECU = één rij in `renault/RenaultEcu.kt`.

## Verhouding tot bestaande tools (aanpak A)

Deze app vult het gat dat de bestaande stack laat liggen (gerichte raw-UDS naar Twizy-ECU's).
Voor generiek werk blijven deze sterker:

- **AndrOBD** (GPL-3.0, F-Droid) — generieke OBD-II, plugins, logging, grafieken.
- **DDT4All** (desktop) — Renault-specifieke ECU-schermen + de XML-database om adressen te verifiëren.

## Status & beperkingen

- Niet compileer-getest in deze omgeving (geen Android SDK beschikbaar); review statisch gedaan.
- ELM327-klonen variëren in ISO-TP-gedrag; `ATFCSM1`-flow-control is daarom expliciet gezet.
- UDS-codeer-/security-access-functies (0x27) zijn bewust **niet** ingebouwd — schrijven naar
  een ECU zonder geverifieerde seed/key-routine kan een module bricken.

## Licentie

Doe ermee wat je wilt. Geen garantie; diagnose op eigen risico.
