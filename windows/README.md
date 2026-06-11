# OpenRLink — Windows 11

Open-source Renault/Twizy diagnose-app voor Windows 11. Praat met je **vLinker FS
(USB-COM, bv. COM8)** of in **demo-modus** zonder hardware. Repliceert de *functies*
van Renault CLIP — niet de gesloten CLIP-database.

## Installatie

1. Installeer **Python 3.10+** (python.org of Microsoft Store). Vink "Add to PATH" aan.
2. In een map met dit project, open een terminal (PowerShell) en run:
   ```
   pip install -r requirements.txt
   ```
   (Enige externe dependency: `pyserial`. De GUI gebruikt Tkinter — zit in Python.)
3. Start de app:
   ```
   python run.py
   ```

## Gebruik

1. **COM-poort** kiezen (knop *Ververs* toont alle poorten met beschrijving) → **Baud**
   115200 voor de vLinker FS (STN1170). Klik **Verbind**.
   - Geen hardware bij de hand? Klik **Demo** om de app te verkennen.
2. Tabs:
   - **OBD** — DTC's scannen/wissen (mode 03/07/04), live data (mode 01).
   - **Twizy / SEVCON** — SEVCON-login + registers lezen; per-ECU DTC's via UDS 0x19.
   - **Terminal** — vrije AT/ST/hex-commando's. `ATMA` = monitor all (sniffen).

## SEVCON — veiligheid (belangrijk)

- **Login + lezen** is geverifieerd tegen OVMS-broncode en veilig.
- De login-sequentie is exact: `Write 0x5000.03=0` → `Write 0x5000.02=0x4BDF`
  → `Read 0x5000.01` moet **4** geven (operator-level).
- **Schrijven is expert-only.** De register-tabel markeert welke je beter niet schrijft.
  `0x4611`/`0x4610` (PMAP/FMAP) zijn de gekoppelde maps die in je v14-firmware corrumpeerden —
  schrijf die nooit los van de volledige curve.

## Te verifiëren: CAN-ID's

De ECU request/response-ID's in `openrlink/renault.py` zijn **startpunten**.
Verifieer via DDT4All-ECU-XML of een live sniff (`ATMA` in de Terminal, of je M5StickC).
Pas ze aan in die ene tabel; de rest volgt.

## Architectuur

```
openrlink/
├── transport.py   Serial (vLinker USB) + Demo  (TCP voor M5Stick = later)
├── elm.py         ELM327/STN engine: init, CAN-headers, ISO-TP config
├── obd.py         OBD-II: PID-decoders, DTC-decoder, ObdService
├── uds.py         UdsClient: ISO-14229 + NRC-tekst
├── sevcon.py      SEVCON Gen4: SDO read/write, login (OVMS-geverifieerd)
├── renault.py     ECU-adrestabel
└── app.py         Tkinter GUI (threaded; bevriest niet tijdens I/O)
run.py             startpunt
```

## .exe maken (optioneel)

Voor een dubbelklik-bestand zonder Python-installatie:
```
pip install pyinstaller
pyinstaller --onefile --windowed --name OpenRLink run.py
```
Resultaat: `dist/OpenRLink.exe`.

## Verhouding tot bestaande tools

- **DDT4All** (al in gebruik) blijft sterker voor Renault-ECU-schermen + de XML-database
  om CAN-ID's te verifiëren.
- Deze app vult aan met directe SEVCON-SDO + raw UDS, met de OVMS-geverifieerde registers.

## Status

- Logica + decoders getest (DTC, PID, demo-flow slagen). GUI syntax-gecontroleerd.
- ELM/STN-klonen variëren in ISO-TP-gedrag; flow-control is expliciet gezet.
- Geen UDS security-access (0x27) ingebouwd — bewust, om bricken te voorkomen.
