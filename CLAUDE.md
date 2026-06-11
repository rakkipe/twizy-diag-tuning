# CLAUDE.md — Twizy diagnose-project

> Dit bestand wordt automatisch door Claude Code ingelezen als projectcontext.
> Houd het kort en accuraat. Werk het bij wanneer beslissingen veranderen.

## Doel

Open-source diagnose-/tuning-gereedschap voor een **Renault Twizy 80 (bwj 2012)** met
**SEVCON Gen4** motorcontroller (CANopen node 1). Repliceert de *functies* van Renault CLIP
— NIET de gesloten CLIP-database (Renault-IP, niet beschikbaar).

Context van de eigenaar: herstel na een TwizyTuner v14-sketch die out-of-range waarden naar
de SEVCON schreef → 0x5044-fout + gecorrumpeerde PMAP/FMAP-maps (0x4611/0x4610). Voertuig
toont nog STOP, gaat niet in GO. UCH lijkt een fout te flaggen vóór de BMS/SEVCON-handshake.

## Hardware

- **vLinker FS** — USB-only (géén Bluetooth!). STN1170-chip, verschijnt als COM-poort
  (bv. COM8), 115200 baud. Wordt ook in DDT4All gebruikt.
- **M5StickC Plus2** (ESP32-PICO-V3-02) + **M5 CAN Unit SKU:U085** (CA-IS3050G, NIET MCP2515).
  CAN-pinnen TX=GPIO32, RX=GPIO33. Native ESP32 **TWAI** driver. 500 kbps.
- Twizy OBD2: pin6=CAN-H, pin14=CAN-L, pin4=GND, pin16=+12V. 120Ω terminator aan M5-zijde.

## Mappenstructuur

```
twizy-diag/
├── CLAUDE.md                  ← dit bestand
├── README.md
├── docs/skill_verificatie.md  ← skill vs OVMS-broncode (BELANGRIJK, zie hieronder)
├── android/                   ← Kotlin/Compose app (USB-OTG of WiFi-brug)
│   ├── app/src/main/java/be/technop/openrlink/...
│   └── firmware/M5CanBridge/  ← !! ONAF: alleen .h-stubs, .ino/.cpp ontbreekt
└── windows/                   ← Python/Tkinter app (GETEST, werkt)
    ├── openrlink/...
    └── run.py
```

## Apps — build & run

**Windows (primair, getest):**
```
cd windows
pip install -r requirements.txt
python run.py
```
.exe: `pyinstaller --onefile --windowed --name OpenRLink run.py`

**Android:** open `android/` in Android Studio (genereert Gradle-wrapper) → assembleDebug.
Let op: huidige BT-transport is voor Bluetooth-adapters; de vLinker FS is USB → voeg een
USB-serial transport toe (lib: `usb-serial-for-android`) OF gebruik de M5-WiFi-brug.

## GEVERIFIEERDE FEITEN (tegen OVMS-broncode — niet wijzigen zonder nieuwe verificatie)

Bron: `openvehicles/Open-Vehicle-Monitoring-System-3` → `vehicle_renaulttwizy/src/rt_sevcon*.cpp`

SEVCON-login (100% correct, veilig):
```
Write 0x5000.03 = 0          (sessie reset)
Write 0x5000.02 = 0x4BDF     (operator wachtwoord)
Read  0x5000.01  → moet 4    (operator-level OK)
```
CANopen SDO: TX-ID 0x601, RX-ID 0x581 (node 1).

Tuning-registers (de SKILL-tabel was hier deels FOUT — gebruik deze i.p.v. de skill):
| Functie | JUIST register | Skill zei (fout) |
|---|---|---|
| Max snelheid vooruit | **0x2920.05** | 0x2920.01 (= drive_level, fout) |
| Peak torque | **0x6076.00** | 0x291C (komt niet voor in OVMS) |
| Torque-curve (POWER MAP) | **0x4611.xx** | — |
| Flux map (max motor torque) | **0x4610.11** (lezen) | — |
| Max current | **0x4641.02** + **0x6075.00** (scaling) | 0x2916 (= rated torque, fout) |
| Neutral braking | **0x3813**.33/.35/.3b | 0x4600 (read-only monitoring) |

## VEILIGHEIDSREGELS (hard)

1. **Nooit SDO-write zonder geslaagde login** (level==4 checken).
2. **PMAP/FMAP (0x4611/0x4610) NOOIT los schrijven** — dit veroorzaakte de v14-corruptie.
   Alleen als volledige, consistente curve.
3. **Geen UDS security-access (0x27)** ingebouwd, bewust — verkeerde seed/key kan een ECU bricken.
4. Bevestigingsdialoog vóór elke write naar de SEVCON.
5. CAN-ID's voor UDS-ECU's (PEB 75A/762, LBC 79B/7BB, BCB, TDB, UCH) zijn STARTPUNTEN →
   verifiëren via DDT4All-XML of live sniff (`ATMA`) vóór vertrouwen.

## CONVENTIES

- Communiceer in het **Nederlands**.
- **Modulair** werken; firmware-versies met nummers tracken; testen en terugrollen.
- **Dubbelchecken**: twee aanpakken of gecombineerd; output altijd testen vóór opleveren.
  (De DTC-decoder had een count-byte-bug die door testen werd gevonden — getest = verplicht.)
- Voorkeur voor **beknopte** antwoorden.

## STATUS & TODO (prioriteit van boven naar onder)

- [x] Windows-app: OBD (DTC lezen/wissen, live data), SEVCON login+lezen, UDS, terminal — getest.
- [x] Android-app: zelfde kern (Kotlin/Compose) — niet compileer-getest.
- [x] Skill geverifieerd tegen OVMS; correcties gedocumenteerd.
- [!] **Te reviewen/testen** — de Android-map bevat transport-bestanden die NIET in de
      laatste sessie zijn geschreven/getest en dus ongeverifieerd zijn:
        - `transport/UsbSerialTransport.kt` (vLinker FS via OTG, lib mik3y usb-serial 3.7.0)
        - `transport/TcpTransport.kt` (naar M5-brug 192.168.4.1:35000)
        - `res/xml/usb_device_filter.xml` (FTDI VID 0x0403)
        - `firmware/M5CanBridge/config.h` + `elm_bridge.h` (alleen headers)
      Inhoudelijk schoon gecontroleerd (geen externe URLs/exfil), maar review + compileer-test
      vereist vóór vertrouwen. `AppViewModel.kt` is al gewired naar USB/TCP — controleer die flow.
- [ ] **M5CanBridge firmware afmaken**: alleen `config.h` + `elm_bridge.h` (stubs) bestaan.
      Nodig: `M5CanBridge.ino` (setup/loop, WiFi-AP, TCP-server) + `elm_bridge.cpp`
      (ELM327-emulatie over TWAI: ATZ/ATE/ATSP/ATCAF/ATSH/ATCRA/ATFC*/ATMA + ISO-TP SF/FF/CF/FC).
- [ ] Windows: TCP-transport naar M5-brug toevoegen (zelfde Transport-interface).
- [ ] SEVCON-leesacties uitbreiden met geverifieerde registers (snelheid 0x2920.05, peak 0x6076).
- [ ] UCH-fout uitdiepen (root cause STOP-toestand vóór BMS/SEVCON-handshake).
- [ ] Optioneel later: Raspberry Pi Zero 2 W + PiCAN als permanente in-car logger.

> NB: tijdens het bundelen bleken er bestanden in `android/` te staan die niet in de
> laatste sessie zijn aangemaakt (zie [!] hierboven). Ze zijn meegenomen omdat ze on-topic
> en schoon zijn, maar behandel ze als ongeverifieerd tot je ze hebt nagelopen/gecompileerd.

## Referenties

- OVMS broncode (geverifieerd): github.com/openvehicles/Open-Vehicle-Monitoring-System-3
- DDT4All v3.0.9 (standard) — voor Renault-ECU-XML en adresverificatie
- AndrOBD (GPL-3.0) — generieke OBD-referentie
- Skill `m5twizy-sevcon` aanwezig in de Claude-omgeving (login/lezen betrouwbaar; schrijf-tabel
  corrigeren volgens docs/skill_verificatie.md)
