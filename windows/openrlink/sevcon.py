"""
SEVCON Gen4 (Twizy, node 1) — CANopen SDO via de adapter.

Voor SDO over een ELM/STN-adapter sturen we raw CAN-frames met ATSH/ATCRA op
de CANopen-ID's (0x601 request / 0x581 respons) en ATCAF0 (geen ISO-TP, kale frame).

!! Registers hieronder zijn geverifieerd tegen OVMS-broncode
   (openvehicles/.../vehicle_renaulttwizy). Zie commentaar per regel.
   Login + LEZEN = veilig. SCHRIJVEN = expert-only; één fout register herhaalt je v14-corruptie.
"""

from __future__ import annotations
from dataclasses import dataclass

# CANopen node 1
SDO_TX = "601"   # client → SEVCON
SDO_RX = "581"   # SEVCON → client


@dataclass
class SdoReg:
    index: int
    sub: int
    label: str
    writable: bool
    note: str = ""


# Geverifieerd tegen OVMS. 'writable=False' = alleen lezen aanraden.
REGISTERS: list[SdoReg] = [
    # --- Login (geverifieerd 100% correct) ---
    SdoReg(0x5000, 0x01, "Login-level (lees: 4=operator)", False, "OVMS rt_sevcon.cpp"),
    # --- Snelheid (LET OP: sub 0x05, NIET 0x01!) ---
    SdoReg(0x2920, 0x05, "Max snelheid vooruit (rpm)", True,
           "OVMS schrijft 0x2920.05. Skill zei .01 = FOUT (dat is drive_level)."),
    # --- Koppel: peak (0x6076) + PMAP-curve (0x4611) ---
    SdoReg(0x6076, 0x00, "Peak torque (Nm-ish)", True,
           "OVMS: Write(0x6076.00). Hoort samen met PMAP 0x4611."),
    SdoReg(0x4611, 0x01, "PMAP torque punt 1", True,
           "POWER MAP — exact wat je v14 corrumpeerde. Alleen met volledige curve schrijven."),
    SdoReg(0x4610, 0x11, "FMAP max motor torque (flux)", False,
           "Flux map — lezen ok, schrijven = zeer riskant."),
    # --- Stroom ---
    SdoReg(0x4641, 0x02, "Current scaling", True, "OVMS: stroomlimiet via 0x4641.02 + 0x6075.00."),
    SdoReg(0x6075, 0x00, "Rated current", True, "OVMS schrijft hier met scaling."),
    # --- Brakedown / neutral braking ---
    SdoReg(0x3813, 0x33, "Neutral braking start (rpm)", True, "OVMS CfgRecup."),
    SdoReg(0x3813, 0x35, "Neutral braking end (rpm)", True, "OVMS CfgRecup."),
    SdoReg(0x3813, 0x3B, "Drive brakedown trigger (rpm)", True, "OVMS CfgRecup."),
]


class Sevcon:
    """SDO read/write via raw CAN-frames over de ELM/STN-adapter."""

    def __init__(self, elm):
        self.elm = elm

    def _prep(self):
        # Kale CAN-frames (geen ISO-TP) op CANopen-ID's.
        self.elm.command("ATSP6")
        self.elm.command("ATCAF0")           # auto-formatting UIT = wij sturen 8 databytes zelf
        self.elm.command(f"ATSH{SDO_TX}")    # zend op 0x601
        self.elm.command(f"ATCRA{SDO_RX}")   # ontvang alleen 0x581

    def read(self, index: int, sub: int) -> int | None:
        """SDO upload (0x40). Geeft 32-bit waarde of None bij abort/timeout."""
        self._prep()
        frame = f"40 {index & 0xFF:02X} {(index >> 8) & 0xFF:02X} {sub:02X} 00 00 00 00"
        raw = self.elm.command(frame.replace(" ", ""))
        by = [int(t, 16) for t in raw.split() if len(t) == 2 and _ok(t)]
        # verwacht respons 0x4x .. ; abort = 0x80
        if len(by) < 8 or by[0] == 0x80:
            return None
        return by[4] | (by[5] << 8) | (by[6] << 16) | (by[7] << 24)

    def write32(self, index: int, sub: int, value: int) -> bool:
        """SDO download 4 bytes (0x23). Geeft True bij bevestiging (0x60)."""
        self._prep()
        frame = (f"23 {index & 0xFF:02X} {(index >> 8) & 0xFF:02X} {sub:02X} "
                 f"{value & 0xFF:02X} {(value >> 8) & 0xFF:02X} "
                 f"{(value >> 16) & 0xFF:02X} {(value >> 24) & 0xFF:02X}")
        raw = self.elm.command(frame.replace(" ", ""))
        by = [int(t, 16) for t in raw.split() if len(t) == 2 and _ok(t)]
        return len(by) >= 1 and by[0] == 0x60

    def login(self) -> bool:
        """Geverifieerde OVMS-sequentie: reset → 0x4BDF → check level==4."""
        self.write32(0x5000, 0x03, 0)
        self.write32(0x5000, 0x02, 0x4BDF)
        level = self.read(0x5000, 0x01)
        return level == 4

    def logout(self) -> bool:
        self.write32(0x5000, 0x03, 0)
        self.write32(0x5000, 0x02, 0)
        return self.read(0x5000, 0x01) == 0


def _ok(s: str) -> bool:
    try:
        int(s, 16); return True
    except ValueError:
        return False
