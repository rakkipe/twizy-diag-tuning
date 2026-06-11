# Twizy diagnose-project

Open-source diagnose- en tuninggereedschap voor de **Renault Twizy 80 (2012)** met
**SEVCON Gen4** controller. Twee apps die dezelfde diagnose-functies bieden, plus firmware
voor de M5StickC als draadloze CAN-brug.

| Onderdeel | Map | Status |
|---|---|---|
| Windows 11-app (Python/Tkinter) | `windows/` | ✅ werkt, getest |
| Android-app (Kotlin/Compose) | `android/` | ⚙️ kern klaar, transport aanpassen |
| M5StickC WiFi↔CAN-brug | `android/firmware/M5CanBridge/` | 🔧 onaf (alleen headers) |
| Skill-verificatie vs OVMS | `docs/skill_verificatie.md` | ✅ |

**Start hier:** lees `CLAUDE.md` — dat bevat de volledige context, de geverifieerde
SEVCON-registers, de veiligheidsregels en de TODO-lijst.

## Snelstart Windows

```
cd windows
pip install -r requirements.txt
python run.py
```

## Belangrijk

- vLinker FS = **USB-only** (COM-poort, 115200 baud), geen Bluetooth.
- SEVCON **login + lezen is veilig en geverifieerd**; schrijven is expert-only.
- PMAP/FMAP (0x4611/0x4610) nooit los schrijven — veroorzaakte de eerdere corruptie.

## Verder werken met Claude Code

```
cd twizy-diag
claude
```
Claude Code leest `CLAUDE.md` automatisch en kan direct verder met de TODO-lijst.
