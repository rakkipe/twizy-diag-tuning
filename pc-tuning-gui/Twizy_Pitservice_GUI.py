#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Twizy Pitservice — Tuning GUI
=============================
Professionele werkplaats-tool voor de Renault Twizy (SEVCON Gen4), via een
vLinker/ELM327 USB-CAN adapter, direct vanaf de pc.

Backend (CANopen SDO over ELM327 met ATCAF0) is gebaseerd op je eigen bewezen
vlinker_twizy_sevcon_canopen_tool.py. De tuning-engine is een getrouwe port van
de OVMS / dexterbg Twizy-Cfg registers (geverifieerd, niet verzonnen).

Functies:
  - Diagnose: status, actieve faults, accu/cap-spanning, temp, DC-spanning
  - Login (operator 0x4BDF)
  - Tuning: profielen (ECO/STOCK/SPORT/RAIN/CITY/CUSTOM) — snelheid/koppel/vermogen/regen
  - Base64-profielen (compatibel met dexters-web.de/cfgedit)
  - Lock-/firmware-check (0x100A)
  - Recovery: fouten wissen, logs wissen, operationeel zetten
  - Console met ruwe SDO r/w

Vereist: Python 3.8+ en pyserial  (pip install pyserial)
Start via run.bat of:  python Twizy_Pitservice_GUI.py
"""

from __future__ import annotations
import queue
import re
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext

try:
    import serial
    import serial.tools.list_ports as list_ports
except ImportError:
    import sys
    try:
        r = tk.Tk(); r.withdraw()
        messagebox.showerror("Twizy Pitservice",
                             "pyserial ontbreekt.\n\nInstalleer met:\n    pip install pyserial")
    except Exception:
        print("pyserial ontbreekt: pip install pyserial")
    sys.exit(1)

DEFAULT_PORT = "COM8"
DEFAULT_BAUD = 115200

# DDT4All-launcher (pip-install met gebundelde Python)
DDT_EXE_CANDIDATES = [
    r"C:\Program Files\ddt4all\Python313-x64\Scripts\ddt4all.exe",
    r"C:\Program Files (x86)\ddt4all\Python386-32\Scripts\ddt4all.exe",
]

# ── Huisstijl (werkplaats / pit) ─────────────────────────────────────────────
BG      = "#0d1117"
PANEL   = "#161b22"
CARD    = "#1f2630"
ACCENT  = "#f2b705"   # pit-geel
ACCENT2 = "#2dd4bf"
OK      = "#22c55e"
WARN    = "#f59e0b"
DANGER  = "#ef4444"
TXT     = "#e6edf3"
MUTED   = "#8b949e"

# Profielen die de M5-firmware zelf kent (APPLY <index> 0..5):
M5_BUILTIN = ["ECO", "STOCK", "SPORT", "RAIN", "CITY", "CUSTOM"]
# GUI-lijst = ingebouwde + extra profielen (die via macro-commando's gaan):
PROFILE_NAMES = M5_BUILTIN + ["110NM", "RACE", "RACE-LIGHT"]

# ═════════════════════════════════════════════════════════════════════════════
# ELM327 / vLinker backend  (getrouw overgenomen uit je werkende tool)
# ═════════════════════════════════════════════════════════════════════════════

def hx(s: str) -> str:
    return re.sub(r"[^0-9A-Fa-f]", "", s).upper()


class Elm:
    def __init__(self, port: str, baud: int = 115200, timeout: float = 1.0):
        self.port = port
        self.baud = baud
        self.timeout = timeout
        self.ser = None

    def open(self):
        self.ser = serial.Serial(self.port, self.baud, timeout=self.timeout)
        time.sleep(0.25)

    def close(self):
        try:
            if self.ser and self.ser.is_open:
                self.ser.close()
        except Exception:
            pass
        self.ser = None

    def is_open(self):
        return self.ser is not None and self.ser.is_open

    def _read_until_prompt(self, max_wait: float = 2.0) -> str:
        deadline = time.time() + max_wait
        data = b""
        while time.time() < deadline:
            chunk = self.ser.read(512)
            if chunk:
                data += chunk
                if b">" in data:
                    break
            else:
                time.sleep(0.01)
        return data.decode(errors="ignore")

    def cmd(self, command: str, wait: float = 2.0):
        self.ser.reset_input_buffer()
        self.ser.write((command.strip() + "\r").encode("ascii", errors="ignore"))
        raw = self._read_until_prompt(max_wait=wait)
        lines = []
        for ln in raw.replace("\r", "\n").split("\n"):
            ln = ln.strip()
            if not ln or ln == ">" or ln == command.strip():
                continue
            lines.append(ln)
        return lines

    def init_canopen(self):
        for c, w in [
            ("ATZ", 2.0), ("ATE0", 1.0), ("ATL0", 1.0), ("ATS0", 1.0),
            ("ATH1", 1.0), ("ATSP6", 1.0), ("ATAT1", 1.0), ("ATAL", 1.0),
            ("ATCAF0", 1.0),   # raw CANopen payload
            ("ATSH601", 1.0),  # SDO request naar node 1
            ("ATCRA581", 1.0), # alleen SDO-antwoorden van node 1
        ]:
            self.cmd(c, wait=w)

    def set_header(self, hdr: str):
        self.cmd("ATSH" + hdr, wait=0.6)

    def set_rx_filter(self, cra: str):
        self.cmd("ATCRA" + cra, wait=0.6)

    def init_isotp(self):
        """ISO-TP modus (ATCAF1) voor UDS/KWP diagnose van de andere ECU's."""
        for c, w in [
            ("ATZ", 2.0), ("ATE0", 1.0), ("ATL0", 1.0), ("ATS0", 1.0),
            ("ATH1", 1.0), ("ATSP6", 1.0), ("ATAT1", 1.0),
            ("ATCAF1", 1.0),   # ISO-TP auto-formatting AAN (ELM doet flow-control)
        ]:
            self.cmd(c, wait=w)


class Sevcon:
    NODE = 1

    def __init__(self, elm: Elm):
        self.elm = elm

    @staticmethod
    def _rd(idx, sub):
        return f"40{idx & 0xFF:02X}{(idx >> 8) & 0xFF:02X}{sub:02X}00000000"

    @staticmethod
    def _wr(idx, sub, val):
        return (f"22{idx & 0xFF:02X}{(idx >> 8) & 0xFF:02X}{sub:02X}"
                f"{val & 0xFF:02X}{(val >> 8) & 0xFF:02X}{(val >> 16) & 0xFF:02X}{(val >> 24) & 0xFF:02X}")

    def _parse(self, line):
        s = hx(line)
        if not s.startswith("581"):
            raise ValueError("geen 0x581")
        body = s[3:]
        if len(body) < 16:
            raise ValueError("te kort")
        b = [int(body[i:i+2], 16) for i in range(0, 16, 2)]
        val = b[4] | (b[5] << 8) | (b[6] << 16) | (b[7] << 24)
        return b[0], b[1], b[2], b[3], val

    def read(self, idx, sub):
        lines = self.elm.cmd(self._rd(idx, sub), wait=2.0)
        if not lines:
            return False, 0, "NO_RESPONSE"
        first = lines[0].upper()
        if "NO DATA" in first: return False, 0, "NO_DATA"
        if "CAN ERROR" in first: return False, 0, "CAN_ERROR"
        try:
            d0, d1, d2, d3, val = self._parse(lines[0])
        except ValueError:
            return False, 0, f"UNPARSED:{lines[0]}"
        if d0 == 0x80:
            return False, val, f"ABORT:{val:08X}"
        return True, val, "OK"

    def write(self, idx, sub, val):
        lines = self.elm.cmd(self._wr(idx, sub, val), wait=2.0)
        if not lines:
            return False, 0, "NO_RESPONSE"
        first = lines[0].upper()
        if "NO DATA" in first: return False, 0, "NO_DATA"
        if "CAN ERROR" in first: return False, 0, "CAN_ERROR"
        try:
            d0, d1, d2, d3, v = self._parse(lines[0])
        except ValueError:
            return False, 0, f"UNPARSED:{lines[0]}"
        if d0 == 0x80:
            return False, v, f"ABORT:{v:08X}"
        if d0 == 0x60 and d1 == (idx & 0xFF) and d2 == ((idx >> 8) & 0xFF) and d3 == sub:
            return True, 0, "OK"
        return False, 0, "UNEXPECTED_ACK"

    def login(self):
        ok, lvl, st = self.read(0x5000, 0x01)
        if ok and lvl == 4:
            return True, "AL_INGELOGD"
        w1 = self.write(0x5000, 0x03, 0)
        if not w1[0]:
            return False, f"RESET_FOUT:{w1[2]}"
        w2 = self.write(0x5000, 0x02, 0x4BDF)
        if not w2[0]:
            return False, f"PWD_FOUT:{w2[2]}"
        ok2, lvl2, st2 = self.read(0x5000, 0x01)
        return (ok2 and lvl2 == 4), ("LOGIN_OK" if (ok2 and lvl2 == 4) else f"VERIFY_FOUT:{lvl2}")

    # ── CANopen state via NMT (COB-ID 0x000) ────────────────────────────────
    def _nmt(self, cmd_byte):
        self.elm.set_header("000")
        self.elm.set_rx_filter("700")            # heartbeat, geen echt antwoord nodig
        self.elm.cmd(f"{cmd_byte:02X}{self.NODE:02X}", wait=0.6)
        self.elm.set_header("601")
        self.elm.set_rx_filter("581")
        time.sleep(0.1)

    def nmt_start(self):
        self._nmt(0x01)                          # NMT Start → operationeel (nodig vóór login/writes)
        return True

    def enter_cfg(self):
        self._nmt(0x80)                          # pre-operationeel
        return True

    def leave_cfg(self):
        self._nmt(0x01)                          # operationeel

    def read_string(self, idx, sub, maxlen=32):
        # expedited/segmented upload voor bv. 0x100A firmwareversie
        lines = self.elm.cmd(self._rd(idx, sub), wait=2.0)
        if not lines:
            return ""
        try:
            d0, d1, d2, d3, val = self._parse(lines[0])
        except ValueError:
            return ""
        out = bytearray()
        if d0 & 0x02:                            # expedited
            n = (4 - ((d0 >> 2) & 0x03)) if (d0 & 0x01) else 4
            for i in range(n):
                out.append((val >> (8 * i)) & 0xFF)
            return out.decode(errors="ignore").strip("\x00")
        # segmented
        toggle = False
        for _ in range(16):
            self.elm.ser.reset_input_buffer()
            seg = self.elm.cmd(f"{0x60 | (0x10 if toggle else 0):02X}00000000000000", wait=1.0)
            if not seg:
                break
            s = hx(seg[0])
            if not s.startswith("581") or len(s) < 3 + 16:
                break
            b = [int(s[3+i:3+i+2], 16) for i in range(0, 16, 2)]   # 8 bytes (cmd + 7 data)
            c = b[0]
            nb = 7 - ((c >> 1) & 0x07)
            for i in range(nb):
                if 1 + i < len(b):
                    out.append(b[1 + i])
            toggle = not toggle
            if c & 0x01:
                break
        return out.decode(errors="ignore").strip("\x00")


# ═════════════════════════════════════════════════════════════════════════════
# Tuning-engine  (getrouwe port van OVMS / Twizy-Cfg registers)
# ═════════════════════════════════════════════════════════════════════════════

CFG80 = dict(
    KphMax=80, RpmMax=7250, RpmRev=900, BrkStart=400, BrkEnd=800, BrkDown=1250,
    KphWarn=89, RpmWarn=8050, WarnOff=550,
    Trq=55000, TrqRated=57000, TrqLim=70125, MapTrq=0,
    CurrLim=450000, CurrStatorMax=450, BoostCurr=540,
    FMAP=[964, 9728, 1122, 9984], EFMAP=[1122, 10089, 2240, 11901],
    PwrLo=12182, PwrLoLim=17000, PwrHi=13000, PwrHiLim=17000, MaxMotorPwr=4608,
    Recup=182, RecupPrc=18, RampStart=400, RampStartPrm=40, RampAccel=2500, RampAccelPrc=25,
    PMAP=[880, 0, 880, 2115, 659, 2700, 608, 3000, 516, 3500, 421, 4500, 360, 5500, 307, 6500, 273, 7250],
)
FIB = [1, 2, 3, 5, 8, 13, 21]


def scale(deflt, frm, to, mn, mx):
    if to == frm:
        return deflt
    v = (deflt * to) // frm
    return mn if v < mn else (mx if v > mx else v)


class Tuner:
    """Voert de OVMS-tuning uit over de Sevcon-backend. log() = callback voor voortgang."""
    def __init__(self, sc: Sevcon, log):
        self.sc = sc
        self.log = log
        self.max_rpm = 0
        self.max_trq = 0
        self.pwr_lo = 0
        self.pwr_hi = 0

    def W(self, idx, sub, val):
        ok, _, st = self.sc.write(idx, sub, val)
        if not ok:
            self.log(f"  WRITE 0x{idx:04X}:{sub:02X}={val} FOUT {st}")
        return ok

    def Wtol(self, idx, sub, val):
        """Tolerante write: logt bij weigering maar breekt de reeks niet af.
        Voor optionele overspeed-/rem-registers die per firmware-variant kunnen
        ontbreken (bv. 0x3813:2D → ABORT 08000000 'General error')."""
        ok, _, st = self.sc.write(idx, sub, val)
        if not ok:
            self.log(f"  (overslaan) 0x{idx:04X}:{sub:02X}={val} {st} — niet ondersteund op deze firmware")
        return True

    def R(self, idx, sub):
        ok, val, st = self.sc.read(idx, sub)
        return ok, val

    # ── speed (pre-op) ──
    def cfg_speed(self, max_kph, warn_kph):
        C = CFG80
        if max_kph == -1: max_kph = C["KphMax"]
        if warn_kph == -1: warn_kph = C["KphWarn"]
        if max_kph < 6 or warn_kph < 6:
            return False
        if self.max_trq == 0:
            ok, v = self.R(0x6076, 0x00)
            if not ok: return False
            self.max_trq = v - C["MapTrq"]
        self._readmaxpwr()
        rpm = scale(C["RpmWarn"], C["KphWarn"], warn_kph, 400, 65535)
        # warn-speed (tolerant: firmware-variant kan subindex weigeren)
        self.Wtol(0x3813, 0x34, rpm)
        self.Wtol(0x3813, 0x3c, rpm - C["WarnOff"])
        rpm = scale(C["RpmMax"], C["KphMax"], max_kph, 400, 65535)
        # essentiële snelheidslimiet — fataal bij fout
        if not self.W(0x2920, 0x05, rpm): return False
        if not self.W(0x2920, 0x06, min(rpm, C["RpmRev"])): return False
        # rem-/overspeed-cluster — tolerant zodat 0x4624:00 (overspeed-trip)
        # altijd nog aan bod komt, ook als een subindex ontbreekt
        self.Wtol(0x3813, 0x33, rpm + C["BrkStart"])
        self.Wtol(0x3813, 0x35, rpm + C["BrkEnd"])
        self.Wtol(0x3813, 0x3b, rpm + C["BrkDown"])
        self.Wtol(0x3813, 0x2d, rpm + C["BrkDown"] + 1500)
        self.Wtol(0x4624, 0x00, rpm + C["BrkDown"] + 2500)
        self.max_rpm = rpm
        return True

    def _readmaxpwr(self):
        C = CFG80
        if self.pwr_lo == 0:
            ok, rpm = self.R(0x4611, 0x04); okd, d = self.R(0x4611, 0x03)
            if ok and okd:
                if d == C["PMAP"][2] and rpm == C["PMAP"][3]:
                    self.pwr_lo = C["PwrLo"]
                elif rpm:
                    self.pwr_lo = (((d * 1000) >> 4) * rpm + (9549 >> 1)) // 9549
        if self.pwr_hi == 0:
            ok, rpm = self.R(0x4611, 0x12); okd, d = self.R(0x4611, 0x11)
            if ok and okd:
                if d == C["PMAP"][16] and rpm == C["PMAP"][17]:
                    self.pwr_hi = C["PwrHi"]
                elif rpm:
                    self.pwr_hi = (((d * 1000) >> 4) * rpm + (9549 >> 1)) // 9549

    # ── power (pre-op) ──
    def cfg_power(self, trq, plo, phi, cur):
        C = CFG80
        limited = (cur == -1)
        if cur == -1: cur = 100
        if trq == -1: trq = 100
        if plo == -1: plo = 100
        if phi == -1: phi = 100
        if self.max_rpm == 0:
            ok, v = self.R(0x2920, 0x05)
            if not ok: return False
            self.max_rpm = v
        if not self.W(0x4641, 0x02, scale(C["CurrStatorMax"], 100, cur, 0, C["BoostCurr"])): return False
        if not self.W(0x6075, 0x00, scale(C["CurrLim"], 100, cur, 0, C["BoostCurr"] * 1000)): return False
        self.max_trq = scale(C["Trq"], 100, trq, 10000, C["TrqLim"] if limited else 200000)
        if not self.W(0x6076, 0x00, self.max_trq + C["MapTrq"]): return False
        if not self.W(0x2916, 0x01, C["TrqRated"] if trq == 100 else self.max_trq + C["MapTrq"]): return False
        self.pwr_lo = scale(C["PwrLo"], 100, plo, 500, C["PwrLoLim"] if limited else 200000)
        self.pwr_hi = scale(C["PwrHi"], 100, phi, 500, C["PwrHiLim"] if limited else 200000)
        mmp = C["MaxMotorPwr"] if (plo == 100 and phi == 100) else int(max(self.pwr_lo, self.pwr_hi) * 0.353)
        # MaxMotorPwr — tolerant: schrijf-beperkte firmware houdt dit op OEM (veilig)
        self.Wtol(0x3813, 0x23, mmp)
        return True

    # ── powermap (pre-op) ──
    def cfg_makepowermap(self):
        C = CFG80
        if (self.max_rpm == C["RpmMax"] and self.max_trq == C["Trq"]
                and self.pwr_lo == C["PwrLo"] and self.pwr_hi == C["PwrHi"]):
            for i in range(18):
                if not self.W(0x4611, 0x01 + i, C["PMAP"][i]): return False
            for i in range(4):
                if not self.W(0x4610, 0x0f + i, C["FMAP"][i]): return False
        else:
            rpm_0 = (self.pwr_lo * 9549 + (self.max_trq >> 1)) // self.max_trq
            trq = (self.max_trq * 16 + 500) // 1000
            for sub, v in [(0x01, trq), (0x02, 0), (0x03, trq), (0x04, rpm_0)]:
                if not self.W(0x4611, sub, v): return False
            fmap = C["EFMAP"] if trq > C["FMAP"][2] else C["FMAP"]
            for i in range(4):
                if not self.W(0x4610, 0x0f + i, fmap[i]): return False
            rpm_d = ((self.max_rpm - rpm_0 + (FIB[6] >> 1)) // FIB[6]) if self.max_rpm > rpm_0 else 0
            pwr_d = (self.pwr_hi - self.pwr_lo + (FIB[5] >> 1)) // FIB[5]
            for i in range(7):
                rpm = (rpm_0 + FIB[i] * rpm_d) if i < 6 else self.max_rpm
                pwr = (self.pwr_lo + FIB[i] * pwr_d) if i < 5 else self.pwr_hi
                trq = (((pwr * 9549 + (rpm >> 1)) // rpm) * 16 + 500) // 1000
                if not self.W(0x4611, 0x05 + (i << 1), trq): return False
                if not self.W(0x4611, 0x06 + (i << 1), rpm): return False
        # powermap-commit/hercalculatie — tolerant: sommige firmwares weigeren dit
        # (ABORT 08000000). De directe limieten (koppel/stroom) zijn dan al gezet.
        self.Wtol(0x4641, 0x01, 1)
        time.sleep(0.05)
        return True

    # ── drive / recup / ramps (operationeel) ──
    def cfg_drive(self, prc):
        if prc == -1: prc = 100
        return self.W(0x2920, 0x01, min(prc * 10, 1000))

    def cfg_recup(self, n, b):
        C = CFG80
        if n == -1: n = C["RecupPrc"]
        if b == -1: b = C["RecupPrc"]
        if not self.W(0x2920, 0x03, scale(C["Recup"], C["RecupPrc"], n, 0, 1000)): return False
        if not self.W(0x2920, 0x04, scale(C["Recup"], C["RecupPrc"], b, 0, 1000)): return False
        return True

    def cfg_ramps(self, st, ac, dc, nt, br):
        C = CFG80
        if st == -1: st = C["RampStartPrm"]
        if ac == -1: ac = C["RampAccelPrc"]
        if dc == -1: dc = 20
        if nt == -1: nt = 40
        if br == -1: br = 40
        if not self.W(0x291c, 0x02, scale(C["RampStart"], C["RampStartPrm"], st, 10, 10000)): return False
        if not self.W(0x2920, 0x07, scale(C["RampAccel"], C["RampAccelPrc"], ac, 10, 10000)): return False
        if not self.W(0x2920, 0x0b, scale(2000, 20, dc, 10, 10000)): return False
        if not self.W(0x2920, 0x0d, scale(4000, 40, nt, 10, 10000)): return False
        if not self.W(0x2920, 0x0e, scale(4000, 40, br, 10, 10000)): return False
        return True

    def apply_profile(self, p):
        """p = dict met speed,warn,torque,power_low,power_high,current,drive,neutral,brake."""
        self.max_rpm = self.max_trq = self.pwr_lo = self.pwr_hi = 0
        self.log("NMT Start (operationeel)…")
        try:
            self.sc.nmt_start()
        except Exception as e:
            self.log(f"  NMT-start waarschuwing: {e}")
        self.log("Login…")
        ok, st = self.sc.login()
        if not ok:
            self.log(f"  LOGIN FOUT: {st}"); return "login"
        self.log("Drive…");  ok = self.cfg_drive(p["drive"]);           #
        if not ok: return "drive"
        self.log("Recup…");  ok = self.cfg_recup(p["neutral"], p["brake"])
        if not ok: return "recup"
        self.log("Ramps…");  ok = self.cfg_ramps(-1, -1, -1, -1, -1)
        if not ok: return "ramps"
        self.log("Pre-operationeel…")
        self.sc.enter_cfg()
        try:
            self.log("Snelheid…"); ok = self.cfg_speed(p["speed"], p["warn"])
            if ok: self.log("Vermogen…"); ok = self.cfg_power(p["torque"], p["power_low"], p["power_high"], p["current"])
            if ok: self.log("Powermap…"); ok = self.cfg_makepowermap()
        finally:
            self.log("Operationeel herstellen…")
            self.sc.leave_cfg()
        return "ok" if ok else "cfg"


# ── Named profielen (OVMS-eenheden; -1 = fabriek) ────────────────────────────
def prof(name, speed, warn, torque, pl, ph, cur, drive, neu, brk):
    return dict(name=name, speed=speed, warn=warn, torque=torque, power_low=pl,
                power_high=ph, current=cur, drive=drive, neutral=neu, brake=brk)

PROFILES = {
    "ECO":    prof("ECO",    55, 60,  70,  70,  70, 100,  70,  40,  40),
    "STOCK":  prof("STOCK",  -1, -1, 100, 100, 100, 100, 100,  18,  18),
    "SPORT":  prof("SPORT",  80, 89, 120, 120, 120, 120, 100,  25,  30),
    "RAIN":   prof("RAIN",   55, 60,  80,  80,  80, 100,  60,  50,  60),
    "CITY":   prof("CITY",   45, 50,  90,  90,  90, 100,  80,  40,  50),
    "CUSTOM": prof("CUSTOM", -1, -1, 100, 100, 100, 100, 100,  18,  18),
    # 110 Nm koppel: 200% van 55 Nm-basis, stroom 123% (~540A) ontgrendelt de
    # limiet, vermogen 139/130% om het te leveren. Zeer agressief — motor-/
    # aandrijflijn-belasting; gebruik op eigen risico, stilstaand in N.
    "110NM":  prof("110NM",  80, 89, 200, 139, 130, 123, 100,  18,  18),
    # SUPER RACE: alles op het maximum — hoge topsnelheid (90 km/h), vol koppel
    # (200% ≈ 110 Nm), vermogen laag/hoog 170%, stroom 123% (~540A boost) en
    # sportieve regen. Dit is de zwaarste stand: motor, tandwielkast, koppeling
    # en accu worden tot/over hun ontwerpgrens belast, bereik daalt sterk.
    # UITSLUITEND stilstaand in N schrijven; rijden op eigen risico.
    "RACE":   prof("RACE",  110, 115, 200, 170, 170, 123, 100,  25,  40),
    # RACE-LIGHT: veiliger tussenstand — 90 km/h, 150% koppel (~82 Nm),
    # vermogen 140%, stroom 110%. Pittig maar minder belastend dan RACE/110NM;
    # goed om mee op te bouwen. Stilstaand in N toepassen.
    "RACE-LIGHT": prof("RACE-LIGHT", 90, 95, 150, 140, 140, 110, 100, 20, 35),
}

# ── Twizy-ECU's voor multi-ECU DTC-scan (geverifieerde diagnose-CAN-ID's) ────
# (naam, tx-request-ID, rx-response-ID) — 11-bit ISO-TP
# Live geverifieerd op de auto (COM3, ISO-TP 11-bit/500k):
#  - LBC/BMS 79B/7BB : UDS 19 02 werkt → leest DTC's
#  - BCB 792/793     : aanwezig, sessie OK, maar geen DTC-service (NRC 12 op alles)
#  - TDB 743/763     : aanwezig, alleen data-service 21, geen DTC-service
#  - UCH 26A/262     : geen antwoord — diagnose-adres nog onbevestigd
#  - PEB/Sevcon      : CANopen (node 1) → zie de Diagnose-tab (0x5300), geen UDS
TWIZY_ECUS = [
    ("LBC  (BMS / accu)",   "79B", "7BB"),
    ("BCB  (lader)",        "792", "793"),
    ("TDB  (display)",      "743", "763"),
    ("UCH  (body) *adres onbevestigd", "26A", "262"),
]


class DtcDb:
    """Vertaalt DTC-codes naar tekst via de LOKALE DDT4All-database van de gebruiker.
    Bundelt zelf GEEN data: leest het ecu.zip-bestand dat de gebruiker al bezit,
    at runtime — net zoals DDT4All. Zonder dat bestand toont de app gewoon de code."""
    CANDIDATES = [r"D:\downloads\ecu.zip", r"D:\downloads\Twizy_Pitservice\ecu.zip"]

    def __init__(self):
        self._map = None
        self.path = None

    def _load(self):
        if self._map is not None:
            return
        self._map = {}
        import os, zipfile, json
        cands = list(self.CANDIDATES)
        cands.append(os.path.join(os.path.dirname(os.path.abspath(__file__)), "ecu.zip"))
        for p in cands:
            if os.path.exists(p):
                self.path = p
                break
        if not self.path:
            return
        try:
            z = zipfile.ZipFile(self.path)
            for f in z.namelist():
                if f.endswith(".json") and not f.endswith(".layout") and ("X09" in f or "Twizy" in f):
                    try:
                        d = json.loads(z.read(f))
                        lst = d.get("data", {}).get("DTCDeviceIdentifier", {}).get("lists", {})
                        for k, v in lst.items():
                            try:
                                self._map[int(k)] = v
                            except Exception:
                                pass
                    except Exception:
                        pass
        except Exception:
            pass

    def available(self):
        self._load()
        return bool(self._map)

    def lookup(self, code_hex):
        """code_hex = 6 hex-tekens (3-byte UDS DTC). Retourneert tekst of None."""
        self._load()
        if not self._map:
            return None
        code_hex = re.sub(r"[^0-9A-Fa-f]", "", code_hex).upper()
        cands = []
        try:
            if len(code_hex) >= 4:
                cands.append(int(code_hex[0:4], 16))                       # eerste 2 bytes
                cands.append(int(code_hex[2:4] + code_hex[0:2], 16))       # byte-swap
            if len(code_hex) >= 6:
                cands.append(int(code_hex, 16))                            # volledige 3 bytes
        except Exception:
            return None
        for c in cands:
            if c in self._map:
                return self._map[c]
        return None


class EcuDiag:
    """Leest DTC's van de Twizy-ECU's via ISO-TP (UDS 0x19 / KWP 0x18). Read-only.
    Start eerst een diagnose-sessie (veel Renault-ECU's weigeren DTC's zonder)."""
    NRC = {"10": "generalReject", "11": "serviceNotSupported", "12": "subFunctionNotSupported",
           "13": "lengte-fout", "22": "conditionsNotCorrect", "31": "requestOutOfRange",
           "33": "securityAccessDenied", "35": "invalidKey", "78": "responsePending",
           "7F": "serviceNotSupportedInSession"}

    def __init__(self, elm: 'Elm', log):
        self.elm = elm
        self.log = log

    def _cmd(self, tx, rx, hexcmd, wait=2.0):
        self.elm.set_header(tx)
        self.elm.set_rx_filter(rx)
        lines = self.elm.cmd(hexcmd, wait=wait)
        return lines, self._joinhex(lines, rx)

    @staticmethod
    def _joinhex(lines, rx):
        buf = []
        for ln in lines:
            s = hx(ln)
            if rx and s.startswith(hx(rx)):
                s = s[len(hx(rx)):]
            buf.append(s)
        return "".join(buf).upper()

    @staticmethod
    def _resp(data):
        """(kind, sid, nrc) uit de UDS/KWP-data: kind = 'pos' | 'neg' | 'none'."""
        if not data:
            return "none", None, None
        # strip een ISO-TP single-frame PCI-nibble (0x0N lengte) indien aanwezig
        d = data
        if len(d) >= 2 and d[0] == "0":
            d = d[2:]
        if d.startswith("7F"):
            return "neg", d[2:4], d[4:6]
        return "pos", d[0:2], None

    def _start_session(self, tx, rx, raw):
        # 1081 = 'StartOfSectionDefaut' (Renault fout-sectie) eerst — nodig voor 17/21-DTC
        for s in ("1081", "10C0", "1003", "1092", "1085"):
            lines, data = self._cmd(tx, rx, s, 1.5)
            raw.append(f"  sessie {s} -> {lines}")
            k, sid, nrc = self._resp(data)
            if k == "pos" and sid == "50":
                return s
        return None

    def probe(self, name, tx, rx):
        """Retourneert dict(present, session, dtcs, note, raw)."""
        raw = []
        # presence: TesterPresent
        lines, data = self._cmd(tx, rx, "3E00", 1.2)
        raw.append(f"  3E00 -> {lines}")
        up = "".join(lines).upper().replace(" ", "")
        present = bool(data) or ("7F" in up) or ("7E" in up)
        if not present and "NODATA" in up:
            return dict(present=False, session=None, dtcs=[], note="geen antwoord", raw="\n".join(raw))

        session = self._start_session(tx, rx, raw)

        # 1) UDS ReadDTCInformation 19 02 (status mask FF)
        lines, data = self._cmd(tx, rx, "1902FF")
        raw.append(f"  1902FF -> {lines}")
        k, sid, nrc = self._resp(data)
        if k == "pos" and sid == "59":
            body = data[data.find("5902") + 6:]
            return dict(present=True, session=session, dtcs=self._parse_uds(body),
                        note="UDS 19 02", raw="\n".join(raw))
        # 1b) UDS 19 0A (alle DTC's)
        if k == "neg" and nrc == "12":
            lines, data = self._cmd(tx, rx, "190A")
            raw.append(f"  190A -> {lines}")
            k2, sid2, _ = self._resp(data)
            if k2 == "pos" and sid2 == "59":
                body = data[data.find("590A") + 4:]
                return dict(present=True, session=session, dtcs=self._parse_uds(body),
                            note="UDS 19 0A", raw="\n".join(raw))
        # 2) KWP readDTCByStatus 18 00 FF 00
        lines, data = self._cmd(tx, rx, "1800FF00")
        raw.append(f"  1800FF00 -> {lines}")
        k, sid, nrc = self._resp(data)
        if k == "pos" and sid == "58":
            i = data.find("58")
            dtcs = self._parse_kwp(data[i + 4:])
            return dict(present=True, session=session, dtcs=dtcs, note="KWP 18", raw="\n".join(raw))
        # 3) KWP readDTC 17 FF 00
        lines, data = self._cmd(tx, rx, "17FF00")
        raw.append(f"  17FF00 -> {lines}")
        k, sid, nrc = self._resp(data)
        if k == "pos" and sid == "57":
            dtcs = self._parse_kwp(data[data.find("57") + 4:])
            return dict(present=True, session=session, dtcs=dtcs, note="KWP 17", raw="\n".join(raw))

        # 4) Renault data-gebaseerde DTC (bv. Cluster/TDB): 21 13 → 61 13 <ruwe data>
        lines, data = self._cmd(tx, rx, "2113")
        raw.append(f"  2113 -> {lines}")
        if "6113" in data:
            body = data[data.find("6113") + 4:]
            note = "Renault 21 13 ruwe foutdata (layout-decode nog nodig)"
            return dict(present=True, session=session, dtcs=[f"raw 6113: {body[:64]}"],
                        note=note, raw="\n".join(raw))

        note = "aanwezig, DTC-dienst geweigerd"
        if nrc:
            note += f" (NRC {nrc} {self.NRC.get(nrc, '?')})"
        return dict(present=True, session=session, dtcs=[], note=note, raw="\n".join(raw))

    @staticmethod
    def _parse_uds(body):
        out = []
        for j in range(0, len(body) - 7, 8):
            code = body[j:j + 6]; status = body[j + 6:j + 8]
            if code and code != "000000":
                out.append(f"{code} (st {status})")
        return out

    @staticmethod
    def _parse_kwp(body):
        out = []
        for j in range(0, len(body) - 5, 6):
            code = body[j:j + 4]; status = body[j + 4:j + 6]
            if code and code != "0000":
                out.append(f"{code} (st {status})")
        return out


FAULTS = {0x0000: "geen", 0x2401: "Login", 0x34C1: "Watchdog", 0x45C3: "Lage accu",
         0x4681: "Pre-op", 0x47C1: "Service nodig", 0x4881: "Stoel", 0x4981: "Gas-fout",
         0x4F01: "Slechte staat", 0x4F41: "Interne fout", 0x5044: "Param dyn range",
         0x51C2: "Precharge fout", 0x5441: "HW/SW mismatch"}


# ═════════════════════════════════════════════════════════════════════════════
# GUI  (worker-thread houdt de interface responsief)
# ═════════════════════════════════════════════════════════════════════════════

class PitserviceGUI(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Twizy Pitservice PRO — Tuning & Diagnose")
        self.geometry("1040x720")
        self.configure(bg=BG)
        self.minsize(900, 600)
        # app-icoon
        try:
            import os as _os
            _ico = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "twizy_pitpro.ico")
            if _os.path.exists(_ico):
                self.iconbitmap(_ico)
        except Exception:
            pass

        self.elm = None
        self.sc = None
        self.connected = False
        self.mode = "elm"               # "elm" = vLinker/ELM327, "m5" = M5StickC Unit-CAN
        self.m5_ser = None              # seriële poort voor M5-modus
        self.m5_running = False
        self.io_lock = threading.RLock() # serialiseert seriële toegang (worker <-> live), reentrant
        self.live_on = False            # live-telemetrie aan/uit
        self.backup = None              # laatste config-backup (dict)
        self.dtcdb = DtcDb()            # DTC→tekst via lokale ecu.zip (leest, bundelt niet)
        self.cmd_q = queue.Queue()      # taken voor de worker
        self.ui_q = queue.Queue()       # resultaten/log naar de UI
        self.worker = threading.Thread(target=self._worker, daemon=True)
        self.worker.start()

        self._style()
        self._topbar()
        self._body()

        self.after(60, self._pump)
        self.protocol("WM_DELETE_WINDOW", self._close)

    # ---- stijl ----
    def _style(self):
        s = ttk.Style(self)
        try: s.theme_use("clam")
        except Exception: pass
        s.configure("TCombobox", fieldbackground=CARD, background=CARD, foreground=TXT, arrowcolor=ACCENT)
        s.map("TCombobox", fieldbackground=[("readonly", CARD)], foreground=[("readonly", TXT)],
              selectbackground=[("readonly", CARD)], selectforeground=[("readonly", TXT)])
        self.option_add("*TCombobox*Listbox.background", CARD)
        self.option_add("*TCombobox*Listbox.foreground", TXT)
        self.option_add("*TCombobox*Listbox.selectBackground", ACCENT2)
        s.configure("TNotebook", background=BG, borderwidth=0)
        s.configure("TNotebook.Tab", background=PANEL, foreground=MUTED, padding=(18, 9),
                    font=("Segoe UI", 10, "bold"))
        s.map("TNotebook.Tab", background=[("selected", CARD)], foreground=[("selected", ACCENT)])

    def _btn(self, parent, text, cmd, color=ACCENT2, fg="#06121f"):
        return tk.Button(parent, text=text, command=cmd, bg=color, fg=fg, activebackground=color,
                         activeforeground=fg, relief="flat", font=("Segoe UI", 10, "bold"),
                         bd=0, padx=14, pady=7, cursor="hand2")

    def _card(self, parent, title):
        c = tk.Frame(parent, bg=CARD)
        tk.Label(c, text=title, bg=CARD, fg=ACCENT, font=("Segoe UI", 11, "bold")).pack(
            anchor="w", padx=12, pady=(10, 4))
        return c

    # ---- topbar ----
    def _topbar(self):
        bar = tk.Frame(self, bg=PANEL); bar.pack(fill="x")
        tk.Label(bar, text="⚑ Twizy Pitservice PRO", bg=PANEL, fg=ACCENT,
                 font=("Segoe UI", 15, "bold")).pack(side="left", padx=14, pady=10)
        tk.Label(bar, text="Adapter:", bg=PANEL, fg=MUTED, font=("Segoe UI", 9)).pack(side="left", padx=(6, 2))
        self.mode_var = tk.StringVar(value="vLinker / ELM327")
        mcb = ttk.Combobox(bar, textvariable=self.mode_var, width=20, state="readonly",
                           values=["vLinker / ELM327", "M5StickC + Unit-CAN"])
        mcb.pack(side="left", padx=(0, 8), pady=10)
        mcb.bind("<<ComboboxSelected>>", lambda e: self._mode_changed())
        self.port_var = tk.StringVar(value=DEFAULT_PORT)
        self.port_cb = ttk.Combobox(bar, textvariable=self.port_var, width=10, state="normal")
        self.port_cb.pack(side="left", padx=(6, 4), pady=10)
        self._btn(bar, "⟳", self._refresh_ports, color=CARD, fg=TXT).pack(side="left")
        self.conn_btn = self._btn(bar, "Verbinden", self._toggle_conn, color=OK)
        self.conn_btn.pack(side="left", padx=8)
        self.status_lbl = tk.Label(bar, text="● Niet verbonden", bg=PANEL, fg=MUTED,
                                   font=("Segoe UI", 10, "bold"))
        self.status_lbl.pack(side="right", padx=14)
        self.busy_lbl = tk.Label(bar, text="", bg=PANEL, fg=ACCENT, font=("Segoe UI", 10, "bold"))
        self.busy_lbl.pack(side="right")
        self._refresh_ports()

    # ---- body ----
    def _body(self):
        nb = ttk.Notebook(self)
        self.t_live = tk.Frame(nb, bg=BG)
        self.t_diag = tk.Frame(nb, bg=BG)
        self.t_tune = tk.Frame(nb, bg=BG)
        self.t_prof = tk.Frame(nb, bg=BG)
        self.t_ecu = tk.Frame(nb, bg=BG)
        self.t_work = tk.Frame(nb, bg=BG)
        self.t_ddt = tk.Frame(nb, bg=BG)
        self.t_rec = tk.Frame(nb, bg=BG)
        for f, t in [(self.t_live, "  Live  "), (self.t_diag, "  Diagnose  "),
                     (self.t_ecu, "  ECU-scan  "), (self.t_tune, "  Tuning  "),
                     (self.t_prof, "  Profielen  "), (self.t_work, "  Werkbon  "),
                     (self.t_ddt, "  DDT4All  "), (self.t_rec, "  Recovery  ")]:
            nb.add(f, text=t)
        self._build_live(self.t_live)
        self._build_diag(self.t_diag)
        self._build_ecuscan(self.t_ecu)
        self._build_tune(self.t_tune)
        self._build_prof(self.t_prof)
        self._build_work(self.t_work)
        self._build_ddt(self.t_ddt)
        self._build_rec(self.t_rec)

        # console — EERST onderaan gereserveerd zodat hij ALTIJD zichtbaar is
        cf = tk.Frame(self, bg=BG); cf.pack(side="bottom", fill="x", padx=10, pady=(0, 8))
        tk.Label(cf, text="Console", bg=BG, fg=MUTED, font=("Segoe UI", 9, "bold")).pack(anchor="w")
        self.console = scrolledtext.ScrolledText(cf, height=8, bg="#05080c", fg="#9fe8c0",
                        insertbackground=TXT, relief="flat", font=("Consolas", 9))
        self.console.pack(fill="x")
        row = tk.Frame(cf, bg=BG); row.pack(fill="x", pady=(4, 0))
        self.raw_var = tk.StringVar()
        e = tk.Entry(row, textvariable=self.raw_var, bg=CARD, fg=TXT, insertbackground=TXT,
                     relief="flat", font=("Consolas", 10))
        e.pack(side="left", fill="x", expand=True, ipady=5, padx=(0, 6))
        e.bind("<Return>", lambda ev: self._raw_cmd())
        self._btn(row, "R/W", self._raw_cmd, color=CARD, fg=TXT).pack(side="right")
        tk.Label(row, text="r <idx> <sub>  |  w <idx> <sub> <val>", bg=BG, fg=MUTED,
                 font=("Consolas", 8)).pack(side="right", padx=8)
        nb.pack(side="top", fill="both", expand=True, padx=10, pady=10)

    # ---- Live-telemetrie dashboard (Pit Pro) ----
    def _build_live(self, tab):
        top = tk.Frame(tab, bg=BG); top.pack(fill="x", padx=6, pady=8)
        self.live_btn = self._btn(top, "▶ Live starten", self._toggle_live, color=OK)
        self.live_btn.pack(side="left")
        tk.Label(top, text="OVMS-meetblok (0x4600/0x4602). Koppel/stroom bewegen alleen in GO tijdens rijden.",
                 bg=BG, fg=MUTED, font=("Segoe UI", 9)).pack(side="left", padx=10)
        self.live_state = tk.Label(top, text="○ uit", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold"))
        self.live_state.pack(side="right", padx=6)

        grid = tk.Frame(tab, bg=BG); grid.pack(fill="both", expand=True, padx=6, pady=6)
        # (sleutel, label, eenheid) — geverifieerde OVMS-meetobjecten (rt_sevcon_mon.cpp)
        self._live_specs = [
            ("bat",  "Accu-spanning", "V"),        # 0x4602:11  /16
            ("cap",  "Cap-spanning", "V"),         # 0x4602:12  /16
            ("mcur", "Motorstroom (AC)", "A"),     # 0x4600:0C  raw
            ("mvlt", "Motorspanning (AC)", "V"),   # 0x4600:0D  /16
            ("mpwr", "Motorvermogen", "kW"),       # V*A/1000
            ("trq",  "Koppel actueel", "Nm"),      # 0x4602:0C  /16
            ("trql", "Koppel-limiet", "Nm"),       # 0x4602:0E  /16
            ("freq", "Uitgangsfreq.", "rad/s"),    # 0x4600:0F  /16
        ]
        self.live_vals = {}
        for i, (key, label, unit) in enumerate(self._live_specs):
            card = tk.Frame(grid, bg=CARD)
            card.grid(row=i // 2, column=i % 2, sticky="nsew", padx=6, pady=6)
            tk.Label(card, text=label, bg=CARD, fg=MUTED, font=("Segoe UI", 10)).pack(anchor="w", padx=14, pady=(10, 0))
            v = tk.Label(card, text="—", bg=CARD, fg=ACCENT, font=("Segoe UI", 26, "bold"))
            v.pack(anchor="w", padx=14)
            tk.Label(card, text=unit, bg=CARD, fg=MUTED, font=("Segoe UI", 9)).pack(anchor="w", padx=14, pady=(0, 10))
            self.live_vals[key] = v
        for c in (0, 1):
            grid.grid_columnconfigure(c, weight=1)
        for r in range((len(self._live_specs) + 1) // 2):
            grid.grid_rowconfigure(r, weight=1)

    # ---- Werkbon / rapport + veilige backup (Pit Pro) ----
    def _build_work(self, tab):
        wrap = tk.Frame(tab, bg=BG); wrap.pack(fill="both", expand=True, padx=8, pady=8)
        form = self._card(wrap, "Werkbon-gegevens"); form.pack(fill="x", pady=6)
        self.work_fields = {}
        for lbl, key in [("Klant", "klant"), ("VIN", "vin"), ("Kilometerstand", "km"),
                         ("Monteur", "monteur")]:
            row = tk.Frame(form, bg=CARD); row.pack(fill="x", padx=12, pady=3)
            tk.Label(row, text=lbl, bg=CARD, fg=MUTED, width=14, anchor="w",
                     font=("Segoe UI", 9)).pack(side="left")
            var = tk.StringVar()
            tk.Entry(row, textvariable=var, bg=PANEL, fg=TXT, insertbackground=TXT,
                     relief="flat", font=("Segoe UI", 10)).pack(side="left", fill="x", expand=True, ipady=4)
            self.work_fields[key] = var
        nrow = tk.Frame(form, bg=CARD); nrow.pack(fill="x", padx=12, pady=(3, 10))
        tk.Label(nrow, text="Notities", bg=CARD, fg=MUTED, width=14, anchor="nw",
                 font=("Segoe UI", 9)).pack(side="left", anchor="n")
        self.work_notes = tk.Text(nrow, height=3, bg=PANEL, fg=TXT, insertbackground=TXT,
                                  relief="flat", font=("Segoe UI", 10))
        self.work_notes.pack(side="left", fill="x", expand=True)

        bkp = self._card(wrap, "Veilige backup (huidige SEVCON-instellingen)"); bkp.pack(fill="x", pady=6)
        tk.Label(bkp, text="Lees en bewaar de huidige tuning vóór je iets schrijft. "
                           "Herstel zet alles in één klik terug.",
                 bg=CARD, fg=MUTED, font=("Segoe UI", 9), justify="left").pack(anchor="w", padx=12)
        brow = tk.Frame(bkp, bg=CARD); brow.pack(fill="x", padx=12, pady=8)
        self._btn(brow, "Backup maken", lambda: self._enqueue(self._task_backup), color=ACCENT2).pack(side="left")
        self._btn(brow, "Backup herstellen", self._restore_backup, color=WARN, fg="#1a1200").pack(side="left", padx=8)
        self.backup_lbl = tk.Label(bkp, text="Nog geen backup.", bg=CARD, fg=MUTED,
                                   font=("Consolas", 9), justify="left")
        self.backup_lbl.pack(anchor="w", padx=12, pady=(0, 10))

        rep = self._card(wrap, "Werkbon exporteren"); rep.pack(fill="x", pady=6)
        tk.Label(rep, text="Leest status + config van de SEVCON en schrijft een .txt-werkbon "
                           "in de map Twizy_Pitservice.",
                 bg=CARD, fg=MUTED, font=("Segoe UI", 9), justify="left").pack(anchor="w", padx=12)
        self._btn(rep, "Genereer werkbon (.txt)", lambda: self._enqueue(self._task_report),
                  color=ACCENT, fg="#1a1200").pack(anchor="w", padx=12, pady=10)

    # ---- Live-telemetrie logica ----
    def _toggle_live(self):
        if self.live_on:
            self.live_on = False
            self.live_btn.config(text="▶ Live starten", bg=OK, fg="#06121f")
            self.live_state.config(text="○ uit", fg=MUTED)
            return
        if not self.connected:
            messagebox.showwarning("Twizy Pitservice", "Eerst verbinden."); return
        self.live_on = True
        self.live_btn.config(text="■ Live stoppen", bg=DANGER, fg="#fff")
        self.live_state.config(text="● live", fg=OK)
        threading.Thread(target=self._live_loop, daemon=True).start()

    def _live_loop(self):
        while self.live_on and self.connected:
            if self._is_m5():
                self._m5_send("TELE")           # firmware print terug via de reader
                time.sleep(1.0)
                continue
            d = {}
            try:
                with self.io_lock:
                    def rd(idx, sub):
                        ok, v, _ = self.sc.read(idx, sub)
                        return self._sgn(v) if ok else None
                    # geverifieerde OVMS-meetobjecten (rt_sevcon_mon.cpp):
                    bat = rd(0x4602, 0x11); d["bat"] = f"{bat/16:.1f}" if bat is not None else "—"
                    cap = rd(0x4602, 0x12); d["cap"] = f"{cap/16:.1f}" if cap is not None else "—"
                    cur = rd(0x4600, 0x0c); d["mcur"] = f"{cur:d}" if cur is not None else "—"
                    mv  = rd(0x4600, 0x0d); d["mvlt"] = f"{mv/16:.1f}" if mv is not None else "—"
                    if cur is not None and mv is not None:
                        d["mpwr"] = f"{(mv/16.0)*cur/1000.0:.2f}"
                    else:
                        d["mpwr"] = "—"
                    trq = rd(0x4602, 0x0c); d["trq"] = f"{trq/16:.1f}" if trq is not None else "—"
                    trl = rd(0x4602, 0x0e); d["trql"] = f"{trl/16:.1f}" if trl is not None else "—"
                    frq = rd(0x4600, 0x0f); d["freq"] = f"{frq/16:.1f}" if frq is not None else "—"
            except Exception as e:
                d = {"freq": f"fout"}
                self.ui_q.put(("log", f"[live] {e}"))
            self.ui_q.put(("live", d))
            time.sleep(1.0)

    @staticmethod
    def _sgn(v):
        return v - 0x100000000 if v & 0x80000000 else v

    @staticmethod
    def _statusword_txt(sw):
        sw &= 0xFFFF
        if sw & 0x08:  return "FOUT"
        if (sw & 0x6f) == 0x27: return "RIJKLAAR (GO)"
        if (sw & 0x6f) == 0x23: return "ingeschakeld"
        if (sw & 0x4f) == 0x40: return "uit (N)"
        if (sw & 0x4f) == 0x21: return "gereed"
        return "—"

    # ---- backup / herstel / werkbon ----
    def _snapshot_config(self):
        """Leest de huidige tuning-registers en bewaart ze als herstelpunt (self.backup)."""
        with self.io_lock:
            self.sc.login()
            regs = {"speed": (0x2920, 0x05), "rev": (0x2920, 0x06), "drive": (0x2920, 0x01),
                    "recup_n": (0x2920, 0x03), "recup_b": (0x2920, 0x04),
                    "torque": (0x6076, 0x00), "trqrated": (0x2916, 0x01),
                    "current": (0x6075, 0x00), "statcur": (0x4641, 0x02),
                    "warn": (0x3813, 0x34)}
            bk = {}
            for k, (idx, sub) in regs.items():
                ok, v, _ = self.sc.read(idx, sub)
                bk[k] = (idx, sub, v) if ok else None
        self.backup = bk
        self.ui_q.put(("backup", bk))
        return bk

    def _task_backup(self):
        if not self._need(): return
        if self._is_m5():
            self.log("M5: backup via GETB64 (kopieer de base64-regel).")
            self._m5_send("GETB64"); return
        self.log("Backup maken van huidige config…")
        bk = self._snapshot_config()
        kph = round(bk["speed"][2] * 80 / 7250) if bk.get("speed") else "?"
        self.log(f"Backup klaar (max ~{kph} km/h). Herstelbaar met één knop.")

    def _restore_backup(self):
        if not self.backup:
            messagebox.showwarning("Twizy Pitservice", "Nog geen backup gemaakt."); return
        if not self.connected:
            messagebox.showwarning("Twizy Pitservice", "Eerst verbinden."); return
        if self._is_m5():
            messagebox.showinfo("Twizy Pitservice", "In M5-modus herstel je met SETB64 <je backup-regel>.")
            return
        if self._confirm("Backup herstellen",
                "De opgeslagen instellingen terugschrijven naar de SEVCON?\nAuto in N, stilstaand."):
            self._enqueue(self._task_restore)

    def _task_restore(self):
        if not self._need(): return
        bk = self.backup
        self.log("Backup terugschrijven…")
        with self.io_lock:
            self.sc.login()
            # snelheid/koppel/stroom vereisen pre-operationeel
            self.sc.enter_cfg()
            try:
                for k in ("speed", "rev", "warn", "torque", "trqrated", "current", "statcur"):
                    if bk.get(k):
                        idx, sub, v = bk[k]
                        ok, _, st = self.sc.write(idx, sub, v)
                        self.log(f"  0x{idx:04X}:{sub:02X}={v} → {'OK' if ok else st}")
            finally:
                self.sc.leave_cfg()
            for k in ("drive", "recup_n", "recup_b"):
                if bk.get(k):
                    idx, sub, v = bk[k]
                    ok, _, st = self.sc.write(idx, sub, v)
                    self.log(f"  0x{idx:04X}:{sub:02X}={v} → {'OK' if ok else st}")
        self.log("Herstel klaar.")

    def _task_report(self):
        if not self._need(): return
        import os, datetime
        ts = datetime.datetime.now()
        lines = ["=" * 52, "  TWIZY PITSERVICE — WERKBON", "=" * 52,
                 f"  Datum      : {ts:%Y-%m-%d %H:%M}",
                 f"  Klant      : {self.work_fields['klant'].get()}",
                 f"  VIN        : {self.work_fields['vin'].get()}",
                 f"  Km-stand   : {self.work_fields['km'].get()}",
                 f"  Monteur    : {self.work_fields['monteur'].get()}",
                 f"  Adapter    : {self.mode_var.get()}", "", "-- METINGEN --"]
        if self._is_m5():
            self.log("Werkbon: M5-modus leest via STATUS/TELE (zie console).")
            self._m5_send("STATUS")
        else:
            with self.io_lock:
                for name, idx, sub, fmt in [
                        ("Login-niveau", 0x5000, 0x01, None), ("Statusword", 0x6041, 0x00, "hex"),
                        ("Accuspanning", 0x5100, 0x01, None), ("DC-bus (V)", 0x6079, 0x00, "dv"),
                        ("Controller-temp", 0x6019, 0x01, None), ("Fault-count", 0x5300, 0x01, None)]:
                    ok, v, st = self.sc.read(idx, sub)
                    if not ok: lines.append(f"  {name:18s}: {st}"); continue
                    if fmt == "hex": lines.append(f"  {name:18s}: 0x{v & 0xFFFF:04X}")
                    elif fmt == "dv": lines.append(f"  {name:18s}: {v/10:.1f} V")
                    else: lines.append(f"  {name:18s}: {v}")
                lines.append(""); lines.append("-- ACTIEVE FAULTS --")
                anyf = False
                for sub in range(0x02, 0x0A):
                    ok, v, _ = self.sc.read(0x5300, sub)
                    if ok and (v & 0xFFFF):
                        anyf = True
                        lines.append(f"  0x5300:{sub:02X} = 0x{v & 0xFFFF:04X} ({FAULTS.get(v & 0xFFFF, '?')})")
                if not anyf: lines.append("  geen")
                lines.append(""); lines.append("-- HUIDIGE CONFIG --")
                ok, rpm, _ = self.sc.read(0x2920, 0x05)
                ok2, dr, _ = self.sc.read(0x2920, 0x01)
                if ok:
                    lines.append(f"  Max snelheid      : ~{round(rpm*80/7250)} km/h ({rpm} rpm)")
                if ok2:
                    lines.append(f"  Rijvermogen       : {dr//10} %")
        notes = self.work_notes.get("1.0", "end").strip()
        if notes:
            lines += ["", "-- NOTITIES --", "  " + notes.replace("\n", "\n  ")]
        lines += ["", "=" * 52]
        folder = os.path.dirname(os.path.abspath(__file__))
        fn = os.path.join(folder, f"werkbon_{ts:%Y%m%d_%H%M%S}.txt")
        try:
            with open(fn, "w", encoding="utf-8") as f:
                f.write("\n".join(lines))
            self.ui_q.put(("toast", f"Werkbon opgeslagen: {os.path.basename(fn)}"))
            self.log(f"Werkbon geschreven: {fn}")
        except Exception as e:
            self.log(f"Werkbon-fout: {e}")

    def _build_diag(self, tab):
        top = tk.Frame(tab, bg=BG); top.pack(fill="x", padx=6, pady=8)
        self._btn(top, "Uitlezen", lambda: self._enqueue(self._task_diag), color=ACCENT, fg="#1a1200").pack(side="left")
        self._btn(top, "🧠 Jev-analyse", lambda: self._enqueue(self._task_jev), color=ACCENT2).pack(side="left", padx=6)
        self.fault_lbl = tk.Label(top, text="", bg=BG, fg=MUTED, font=("Segoe UI", 10, "bold"))
        self.fault_lbl.pack(side="right")
        card = self._card(tab, "SEVCON status"); card.pack(fill="both", expand=True, padx=6, pady=6)
        self.diag_txt = scrolledtext.ScrolledText(card, height=14, bg="#05080c", fg=TXT,
                        relief="flat", font=("Consolas", 10))
        self.diag_txt.pack(fill="both", expand=True, padx=10, pady=(0, 10))

    def _build_tune(self, tab):
        top = tk.Frame(tab, bg=BG); top.pack(fill="x", padx=6, pady=10)
        tk.Label(top, text="Profiel:", bg=BG, fg=TXT, font=("Segoe UI", 10, "bold")).pack(side="left")
        self.profile_var = tk.StringVar(value="STOCK")
        cb = ttk.Combobox(top, textvariable=self.profile_var, values=PROFILE_NAMES, width=12, state="readonly")
        cb.pack(side="left", padx=8)
        cb.bind("<<ComboboxSelected>>", lambda e: self._show_profile())
        self._btn(top, "Lees SEVCON", lambda: self._enqueue(self._task_readcfg), color=CARD, fg=TXT).pack(side="left")

        info = self._card(tab, "Profielwaarden"); info.pack(fill="both", expand=True, padx=6, pady=6)
        self.prof_txt = scrolledtext.ScrolledText(info, height=10, bg="#05080c", fg=TXT,
                        relief="flat", font=("Consolas", 10))
        self.prof_txt.pack(fill="both", expand=True, padx=10, pady=(0, 10))

        bar = tk.Frame(tab, bg=BG); bar.pack(fill="x", padx=6, pady=(0, 8))
        tk.Label(bar, text="⚠ Auto in N (niet GO), stilstaand. Kan toelating/verzekering raken.",
                 bg=BG, fg=WARN, font=("Segoe UI", 9)).pack(side="left")
        self._btn(bar, "PROFIEL TOEPASSEN", self._apply_profile, color=DANGER, fg="#fff").pack(side="right")
        self.sel_lbl = tk.Label(bar, textvariable=self.profile_var, bg=BG, fg=ACCENT,
                                font=("Segoe UI", 11, "bold")); self.sel_lbl.pack(side="right", padx=12)
        tk.Label(bar, text="Gekozen:", bg=BG, fg=MUTED, font=("Segoe UI", 9)).pack(side="right")
        self._show_profile()

    def _build_prof(self, tab):
        c = self._card(tab, "Base64-profiel (dexters-web.de/cfgedit)")
        c.pack(fill="x", padx=6, pady=10)
        tk.Label(c, text="Plak een base64-profiel uit de online editor en schrijf het (auto in N).",
                 bg=CARD, fg=MUTED, font=("Segoe UI", 9)).pack(anchor="w", padx=12)
        row = tk.Frame(c, bg=CARD); row.pack(fill="x", padx=12, pady=10)
        self.b64_var = tk.StringVar()
        tk.Entry(row, textvariable=self.b64_var, bg=PANEL, fg=TXT, insertbackground=TXT,
                 relief="flat", font=("Consolas", 9)).pack(side="left", fill="x", expand=True, ipady=6, padx=(0, 6))
        self._btn(row, "Schrijf profiel", self._apply_b64, color=DANGER, fg="#fff").pack(side="right")
        note = self._card(tab, "Info"); note.pack(fill="x", padx=6, pady=6)
        tk.Label(note, text="Base64-profielen bevatten de volledige tuning-set (snelheid, koppel,\n"
                            "vermogen, regen, ramps, tsmap). Ze worden toegepast met dezelfde\n"
                            "veilige volgorde als de ingebouwde profielen.",
                 bg=CARD, fg=MUTED, font=("Segoe UI", 9), justify="left").pack(anchor="w", padx=12, pady=(0, 10))

    # ---- native multi-ECU DTC-scan (ISO-TP UDS/KWP) ----
    def _build_ecuscan(self, tab):
        top = tk.Frame(tab, bg=BG); top.pack(fill="x", padx=6, pady=8)
        self._btn(top, "🔍 Scan alle ECU's", lambda: self._enqueue(self._task_ecuscan),
                  color=ACCENT, fg="#1a1200").pack(side="left")
        tk.Label(top, text="Leest DTC's van UCH, BMS, lader, display en Sevcon (read-only, geen writes).",
                 bg=BG, fg=MUTED, font=("Segoe UI", 9)).pack(side="left", padx=10)
        card = self._card(tab, "ECU-foutcodes (DTC)"); card.pack(fill="both", expand=True, padx=6, pady=6)
        self.ecu_txt = scrolledtext.ScrolledText(card, height=16, bg="#05080c", fg=TXT,
                        relief="flat", font=("Consolas", 10))
        self.ecu_txt.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        self.ecu_txt.insert("end",
            "Druk op 'Scan alle ECU's' (auto op READY, vLinker verbonden).\n"
            "Live geverifieerd: BMS/LBC geeft DTC's via UDS; BCB (lader) en TDB (display)\n"
            "reageren maar hebben geen DTC-service. Sevcon-fouten staan onder de Diagnose-tab\n"
            "(die praat CANopen, geen UDS). UCH-adres is nog onbevestigd.\n")

    def _task_ecuscan(self):
        if not self._need(): return
        if self._is_m5():
            self.log("ECU-scan werkt in vLinker/ELM327-modus."); return
        self.ui_q.put(("ecuclear", None))
        self.ui_q.put(("ecu", "── ECU-scan (ISO-TP UDS/KWP) ──"))
        self.log("ECU-scan: omschakelen naar ISO-TP…")
        try:
            self.elm.init_isotp()
            diag = EcuDiag(self.elm, self.log)
            for name, tx, rx in TWIZY_ECUS:
                self.ui_q.put(("ecu", f"\n▶ {name}   (tx {tx} / rx {rx})"))
                r = diag.probe(name, tx, rx)
                if not r["present"]:
                    self.ui_q.put(("ecu", "   geen antwoord (ECU uit / ander adres / niet op deze bus)"))
                else:
                    sess = f" [sessie {r['session']}]" if r.get("session") else ""
                    if r["dtcs"]:
                        self.ui_q.put(("ecu", f"   {r['note']}{sess}: {len(r['dtcs'])} DTC('s):"))
                        for d in r["dtcs"]:
                            code = d.split()[0] if d else ""
                            txt = self.dtcdb.lookup(code) if code else None
                            self.ui_q.put(("ecu", f"     • {d}" + (f"  → {txt}" if txt else "")))
                    else:
                        self.ui_q.put(("ecu", f"   {r['note']}{sess}"))
                for rl in r["raw"].splitlines():
                    self.log(rl)
        except Exception as e:
            self.ui_q.put(("ecu", f"Scan-fout: {e}"))
        finally:
            # SEVCON-modus (CANopen) herstellen zodat de andere tabs blijven werken
            self.log("ECU-scan klaar — CANopen-modus herstellen voor SEVCON…")
            try:
                self.elm.init_canopen()
                self.sc.nmt_start()
            except Exception as e:
                self.log(f"  herstel-waarschuwing: {e}")

    # ---- DDT4All-integratie (launcher + poort-overdracht + runbook) ----
    def _ddt_exe(self):
        import os
        for p in DDT_EXE_CANDIDATES:
            if os.path.exists(p):
                return p
        return None

    def _build_ddt(self, tab):
        wrap = tk.Frame(tab, bg=BG); wrap.pack(fill="both", expand=True, padx=8, pady=8)
        head = self._card(wrap, "DDT4All — volledige ECU-diagnose (UCH/BMS/BCB/Sevcon)")
        head.pack(fill="x", pady=6)
        exe = self._ddt_exe()
        tk.Label(head, text=("Gevonden: " + exe) if exe else
                 "DDT4All niet gevonden op de standaardpaden.",
                 bg=CARD, fg=(MUTED if exe else DANGER), font=("Consolas", 8),
                 justify="left", wraplength=900).pack(anchor="w", padx=12)
        tk.Label(head, text="Pitservice en DDT4All kunnen de COM-poort niet delen. "
                            "Bij starten geeft Pitservice de poort automatisch vrij.",
                 bg=CARD, fg=MUTED, font=("Segoe UI", 9), justify="left").pack(anchor="w", padx=12, pady=(2, 0))
        brow = tk.Frame(head, bg=CARD); brow.pack(fill="x", padx=12, pady=10)
        self._btn(brow, "▶ Start DDT4All", lambda: self._enqueue(self._task_launch_ddt),
                  color=ACCENT, fg="#1a1200").pack(side="left")
        self._btn(brow, "Map openen", self._open_ddt_folder, color=CARD, fg=TXT).pack(side="left", padx=8)

        rb = self._card(wrap, "Runbook — veilig uitlezen (read-first)"); rb.pack(fill="both", expand=True, pady=6)
        steps = ("1)  Twizy op READY (niet alleen ACC), vLinker via OBD aangesloten.\n"
                 "2)  DDT4All: interface ELM327/STN, COM-poort zoals je vLinker (bv. COM8), CAN 500 kbps.\n"
                 "3)  Maak eerst een VOLLEDIGE ECU-scan en exporteer/sla het resultaat op.\n"
                 "4)  Open per ECU de foutpagina (DTC): noteer actieve + historische fouten en status.\n"
                 "5)  Wis in de eerste ronde NIETS.\n\n"
                 "Tweede ronde (pas met snapshot):\n"
                 "6)  Wis fouten per ECU (niet blind globaal). Contact 60 s uit, dan READY.\n"
                 "7)  Lees direct opnieuw uit; markeer welke fouten meteen terugkeren.\n\n"
                 "NOOIT: writes in Configuration/Parameters/Calibration, actuator-tests die de\n"
                 "contactor/HV schakelen, of onbekende \"Write\"-knoppen op Sevcon-pagina's.")
        t = scrolledtext.ScrolledText(rb, height=14, bg="#05080c", fg=TXT, relief="flat",
                                      font=("Consolas", 10))
        t.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        t.insert("end", steps); t.configure(state="disabled")

    def _open_ddt_folder(self):
        import os, subprocess
        exe = self._ddt_exe()
        if exe:
            try:
                subprocess.Popen(["explorer", os.path.dirname(exe)])
            except Exception as e:
                self.log(f"Map openen fout: {e}")
        else:
            messagebox.showwarning("Twizy Pitservice", "DDT4All niet gevonden.")

    def _task_launch_ddt(self):
        import os, subprocess
        exe = self._ddt_exe()
        if not exe:
            self.log("DDT4All niet gevonden op de standaardpaden."); return
        if self.connected:
            self.log("Pitservice geeft de COM-poort vrij voor DDT4All…")
            self._task_disconnect()
            time.sleep(0.6)
        try:
            subprocess.Popen([exe], cwd=os.path.dirname(exe))
            self.log("DDT4All gestart. Volg het runbook: eerst uitlezen, niets schrijven.")
        except Exception as e:
            self.log(f"DDT4All start-fout: {e}")

    def _build_rec(self, tab):
        wrap = tk.Frame(tab, bg=BG); wrap.pack(fill="both", expand=True, padx=10, pady=10)
        specs = [
            ("Inloggen (operator 0x4BDF)", "Operator-login (niveau 4) — nodig voor schrijven.", "Inloggen", ACCENT2, self._task_login),
            ("Firmware / schrijfslot", "Leest SW-versie (0x100A). Twizy's na juni 2016 kunnen op slot zitten.", "Lock-check", ACCENT2, self._task_lock),
            ("Fouten wissen", "Wist foutgeschiedenis (0x1003:00=0). Stilstaand.", "Fouten wissen", WARN, self._task_clearhist),
            ("Logs wissen", "Reset SEVCON-logs (0x4110/4100/4200/4300). Stilstaand.", "Logs wissen", WARN, self._task_clearlogs),
            ("Operationeel zetten", "Zet de SEVCON operationeel (0x2800:00=0) + NMT start.", "Operationeel", OK, self._task_setop),
        ]
        for title, desc, btn, col, task in specs:
            card = self._card(wrap, title); card.pack(fill="x", pady=5)
            tk.Label(card, text=desc, bg=CARD, fg=MUTED, font=("Segoe UI", 9)).pack(anchor="w", padx=12)
            fg = "#1a1200" if col in (WARN, ACCENT) else "#06121f"
            self._btn(card, btn, (lambda t=task: self._enqueue(t)), color=col, fg=fg).pack(anchor="w", padx=12, pady=10)

    # ================= profiel-weergave =================
    def _show_profile(self):
        p = PROFILES.get(self.profile_var.get())
        if not p:
            return
        def d(v): return "auto (fabriek)" if v == -1 else str(v)
        self.prof_txt.delete("1.0", "end")
        self.prof_txt.insert("end",
            f"Profiel: {p['name']}\n\n"
            f"  Snelheid        : {d(p['speed'])} km/h   (warn {d(p['warn'])})\n"
            f"  Rijvermogen     : {d(p['drive'])} %\n"
            f"  Koppel          : {d(p['torque'])} %\n"
            f"  Vermogen laag   : {d(p['power_low'])} %\n"
            f"  Vermogen hoog   : {d(p['power_high'])} %\n"
            f"  Stroomlimiet    : {d(p['current'])} %\n"
            f"  Regen neutraal  : {d(p['neutral'])} %\n"
            f"  Regen rem       : {d(p['brake'])} %\n")

    # ================= connectie =================
    def _refresh_ports(self):
        try:
            ports = [p.device for p in list_ports.comports()]
        except Exception:
            ports = []
        self.port_cb["values"] = ports
        if ports and self.port_var.get() not in ports and DEFAULT_PORT not in ports:
            self.port_var.set(ports[-1])

    def _toggle_conn(self):
        if self.connected:
            self._enqueue(self._task_disconnect)
        elif getattr(self, "_connecting", False):
            self.log("Bezig met verbinden — even geduld…")
        else:
            self._connecting = True
            self._enqueue(self._task_connect)

    def _mode_changed(self):
        self.mode = "m5" if "M5" in self.mode_var.get() else "elm"
        if not self.connected:
            self.port_var.set("COM4" if self.mode == "m5" else DEFAULT_PORT)
        self.log(f"Adapter: {self.mode_var.get()}")

    def _is_m5(self):
        return self.mode == "m5"

    # ---- M5StickC (TwizyTool firmware) backend: hoog-niveau tekstcommando's ----
    def _m5_send(self, cmd):
        try:
            if self.m5_ser and self.m5_ser.is_open:
                self.m5_ser.write((cmd + "\n").encode())
        except Exception as e:
            self.log(f"[M5 tx fout] {e}")

    def _m5_reader(self):
        buf = b""
        while self.m5_running:
            try:
                data = self.m5_ser.read(256)
                if data:
                    buf += data
                    while b"\n" in buf:
                        line, buf = buf.split(b"\n", 1)
                        s = line.decode(errors="replace").strip()
                        if s:
                            self.ui_q.put(("log", s))
                else:
                    time.sleep(0.01)
            except Exception:
                self.m5_running = False
                self.ui_q.put(("conn", (False, "M5 verbinding verbroken")))
                break

    # ================= worker-thread =================
    def _enqueue(self, task):
        self.cmd_q.put(task)

    def _worker(self):
        while True:
            task = self.cmd_q.get()
            try:
                self.ui_q.put(("busy", "● bezig…"))
                with self.io_lock:
                    task()
            except Exception as e:
                self.ui_q.put(("log", f"[fout] {e}"))
            finally:
                self.ui_q.put(("busy", ""))

    def log(self, s):
        self.ui_q.put(("log", s))

    # ---- tasks (draaien in worker-thread) ----
    def _task_connect(self):
        port = self.port_var.get().strip()
        if not port:
            self.log("Geen poort gekozen."); self._connecting = False; return
        if self._is_m5():
            self.log(f"M5StickC verbinden met {port} @115200…")
            try:
                self.m5_ser = serial.Serial(port, 115200, timeout=0.1)
                time.sleep(0.3)
                self.m5_running = True
                threading.Thread(target=self._m5_reader, daemon=True).start()
                self.connected = True
                self.ui_q.put(("conn", (True, port + " (M5)")))
                self._m5_send("PING")
            except Exception as e:
                self.connected = False
                self.ui_q.put(("conn", (False, str(e))))
                self.log(f"Verbindingsfout: {e}")
            return
        self.log(f"vLinker verbinden met {port}…")
        try:
            self.elm = Elm(port, DEFAULT_BAUD, 1.0)
            self.elm.open()
            self.elm.init_canopen()
            self.sc = Sevcon(self.elm)
            try:
                self.sc.nmt_start()              # bus operationeel zetten vóór login/writes
            except Exception:
                pass
            ok, lvl, st = self.sc.read(0x5000, 0x01)
            self.connected = True
            self.ui_q.put(("conn", (True, port)))
            if ok:
                self.log(f"Verbonden. Login-niveau nu: {lvl}")
            else:
                self.log(f"Verbonden. SEVCON antwoordt nog niet op SDO ({st}) — contact aan?")
        except Exception as e:
            self.connected = False
            self.ui_q.put(("conn", (False, str(e))))
            self.log(f"Verbindingsfout: {e}")

    def _task_disconnect(self):
        self.live_on = False
        self.m5_running = False
        try:
            if self.m5_ser and self.m5_ser.is_open:
                self.m5_ser.close()
        except Exception:
            pass
        self.m5_ser = None
        if self.elm:
            self.elm.close()
        self.connected = False
        self.sc = None
        self.ui_q.put(("conn", (False, "")))
        self.log("Verbroken.")

    def _need(self):
        if not self.connected:
            self.log("Nog niet verbonden."); return False
        if self._is_m5():
            return self.m5_ser is not None
        return self.sc is not None

    def _task_diag(self):
        if not self._need(): return
        if self._is_m5():
            self.log("M5 diagnose (STATUS + TELE)…")
            for c in ("STATUS", "TELE"):
                self._m5_send(c); time.sleep(0.3)
            return
        self.ui_q.put(("diagclear", None))
        self.log("Diagnose uitlezen…")
        fields = [("Login-niveau", 0x5000, 0x01, None),
                  ("Statusword", 0x6041, 0x00, "hex"),
                  ("Foutregister", 0x1001, 0x00, "hex"),
                  ("Accuspanning", 0x5100, 0x01, None),
                  ("Cap-spanning", 0x5100, 0x03, None),
                  ("DC-spanning (dV)", 0x6079, 0x00, "dv"),
                  ("Controller-temp", 0x6019, 0x01, None),
                  ("Fault-count", 0x5300, 0x01, None)]
        faults_present = False
        for name, idx, sub, fmt in fields:
            ok, val, st = self.sc.read(idx, sub)
            if ok:
                if fmt == "hex":
                    self.ui_q.put(("diag", f"{name:18s} = 0x{val & 0xFFFF:04X}"))
                elif fmt == "dv":
                    self.ui_q.put(("diag", f"{name:18s} = {val/10:.1f} V"))
                else:
                    self.ui_q.put(("diag", f"{name:18s} = {val}"))
            else:
                self.ui_q.put(("diag", f"{name:18s} : {st}"))
        self.ui_q.put(("diag", ""))
        self.ui_q.put(("diag", "-- Actieve faults 0x5300:02..09 --"))
        for sub in range(0x02, 0x0A):
            ok, val, st = self.sc.read(0x5300, sub)
            if ok:
                code = val & 0xFFFF
                if code != 0:
                    faults_present = True
                    self.ui_q.put(("diag", f"  0x5300:{sub:02X} = 0x{code:04X} ({FAULTS.get(code, '?')})"))
        self.ui_q.put(("faults", faults_present))
        self.log("Diagnose klaar.")

    def _task_jev(self):
        """Stuurt de huidige SEVCON-status als 'state' naar Jev (TypeSafe AI) en
        toont getypte beslissingen met confidence. Alleen advies, geen acties."""
        if not self._need(): return
        if self._is_m5():
            self.log("Jev-analyse werkt in vLinker/ELM327-modus (leest SDO-status)."); return
        import os
        try:
            from typesafe_sdk import TypeSafeClient, Choice, Score, Noul
        except ImportError:
            self.log("typesafe-sdk ontbreekt — installeer met: pip install typesafe-sdk"); return
        if not os.environ.get("TYPESAFE_API_KEY"):
            self.log("Geen TYPESAFE_API_KEY. Zet die met setx en herstart de app."); return

        self.log("Jev-analyse: status verzamelen…")
        parts = []
        ok, lvl, _ = self.sc.read(0x5000, 0x01); parts.append(f"login-niveau={lvl if ok else '?'}")
        ok, sw, _ = self.sc.read(0x6041, 0x00); parts.append(f"statusword=0x{sw & 0xFFFF:04X}" if ok else "statusword=?")
        ok, er, _ = self.sc.read(0x1001, 0x00); parts.append(f"errorregister=0x{er & 0xFF:02X}" if ok else "errorregister=?")
        ok, fc, _ = self.sc.read(0x5300, 0x01); parts.append(f"faultcount={fc if ok else '?'}")
        faults = []
        for sub in range(0x02, 0x0A):
            ok, v, _ = self.sc.read(0x5300, sub)
            if ok and (v & 0xFFFF):
                code = v & 0xFFFF; faults.append(f"0x{code:04X}({FAULTS.get(code, '?')})")
        parts.append("actieve_faults=" + (",".join(faults) if faults else "geen"))
        ok, rpm, _ = self.sc.read(0x2920, 0x05)
        if ok: parts.append(f"max_snelheid=~{round(rpm * 80 / 7250)}km/h")
        ok, trq, _ = self.sc.read(0x6076, 0x00)
        if ok: parts.append(f"koppel={trq / 1000:.0f}Nm")
        ok, cur, _ = self.sc.read(0x6075, 0x00)
        if ok: parts.append(f"stroomlimiet={cur / 1000:.0f}A")
        state = "Renault Twizy 80, SEVCON Gen4. Live diagnose: " + "; ".join(parts) + "."
        self.log("state → " + state)

        try:
            client = TypeSafeClient()
            resp = client.system_one(
                state=state,
                questions={
                    "subsysteem": Choice(
                        instructions="Welk subsysteem is de meest waarschijnlijke oorzaak van een probleem",
                        criteria={
                            "sevcon": "Motorcontroller / tuning",
                            "bms": "Accu / laadbalans",
                            "uch_bcb": "Body-computer of handshake bovenstrooms (UCH/BCB)",
                            "geen": "Geen probleem zichtbaar",
                        }),
                    "ernst": Score(
                        instructions="Hoe ernstig is de situatie voor de bestuurder",
                        criteria=["Normaal / rijklaar", "Aandacht nodig", "Rijden geblokkeerd"]),
                    "rijklaar": Noul(
                        instructions="De auto lijkt rijklaar zonder blokkerende fout"),
                })
            a = resp.answers
            sub = a["subsysteem"]; ern = a["ernst"]; rij = a["rijklaar"]
            self.log("── Jev-analyse ──")
            self.log(f"★ Waarschijnlijk subsysteem : {sub.choice}  (confidence {sub.confidence:.0%})")
            self.log(f"★ Ernst (0-2)               : {ern.score:.2f}  (confidence {ern.confidence:.0%})")
            self.log(f"★ Rijklaar (0-1)            : {rij.noul:.2f}")
            self.log(f"  (Jev {getattr(resp, 'model', '?')}, tokens {resp.usage})")
        except Exception as e:
            self.log(f"Jev-fout: {e}")

    def _task_readcfg(self):
        if not self._need(): return
        if self._is_m5():
            self._m5_send("READ"); return
        self.log("Huidige SEVCON-config lezen…")
        ok, rpm, _ = self.sc.read(0x2920, 0x05)
        ok2, drive, _ = self.sc.read(0x2920, 0x01)
        ok3, rn, _ = self.sc.read(0x2920, 0x03)
        ok4, rb, _ = self.sc.read(0x2920, 0x04)
        if ok:
            kph = round(rpm * 80 / 7250)
            self.log(f"  max: {rpm} rpm (~{kph} km/h) | drive {drive//10 if ok2 else '?'}% | recup {rn if ok3 else '?'}/{rb if ok4 else '?'}")
        else:
            self.log("  Geen antwoord (login/contact?).")

    def _task_login(self):
        if not self._need(): return
        if self._is_m5():
            self._m5_send("LOGIN"); return
        self.log("Login…")
        ok, st = self.sc.login()
        self.log(f"  {'OK' if ok else 'FOUT'}: {st}")

    def _task_lock(self):
        if not self._need(): return
        if self._is_m5():
            self._m5_send("LOCK"); return
        self.log("Firmware/lock-check (0x100A)…")
        ver = self.sc.read_string(0x100A, 0x00)
        if not ver:
            self.log("  Geen versie gelezen."); return
        locked = False
        m = re.match(r"(\d+)\.(\d+)", ver)
        if m:
            major, minor = int(m.group(1)), int(m.group(2))
            locked = major > 712 or (major == 712 and minor >= 3)
        self.log(f"  SW-versie: {ver}  →  {'OP SLOT (na juni 2016)' if locked else 'SCHRIJFBAAR'}")

    def _task_clearhist(self):
        if not self._need(): return
        if self._is_m5():
            self._m5_send("CLEARFAULTS"); return
        self.log("Fouten wissen…")
        self.sc.login()
        ok, _, st = self.sc.write(0x1003, 0x00, 0)
        self.log(f"  0x1003:00=0 → {'OK' if ok else st}")

    def _task_clearlogs(self):
        if not self._need(): return
        if self._is_m5():
            self.log("Logs wissen is een vLinker/ELM327-functie; M5 gebruikt CLEARFAULTS.")
            self._m5_send("CLEARFAULTS"); return
        self.log("Logs wissen…")
        self.sc.login()
        for idx, name in [(0x4110, "Fault FIFO"), (0x4100, "System FIFO"),
                          (0x4200, "Event counters"), (0x4300, "Min/max")]:
            ok, _, st = self.sc.write(idx, 0x01, 1)
            self.log(f"  {name:16s} 0x{idx:04X}:01=1 → {'OK' if ok else st}")

    def _task_setop(self):
        if not self._need(): return
        if self._is_m5():
            self.log("M5-modus: operationeel herstel gebeurt automatisch na tuning/logout.")
            return
        self.log("Operationeel zetten…")
        self.sc.login()
        ok, _, st = self.sc.write(0x2800, 0x00, 0)
        self.log(f"  0x2800:00=0 → {'OK' if ok else st}")
        self.sc.leave_cfg()
        oksw, sw, _ = self.sc.read(0x6041, 0x00)
        if oksw:
            self.log(f"  Statusword: 0x{sw & 0xFFFF:04X}")

    def _task_apply(self, profile):
        if not self._need(): return
        if self._is_m5():
            name = profile["name"]
            if name in M5_BUILTIN:
                self.log(f"M5: APPLY {M5_BUILTIN.index(name)} ({name})")
                self._m5_send(f"APPLY {M5_BUILTIN.index(name)}")
            else:
                # custom profiel (bv. 110NM) via de macro-commando's van de firmware
                self.log(f"M5: custom profiel {name} via SPEED/POWER/RECUP")
                self._m5_send("LOGIN"); time.sleep(0.4)
                self._m5_send(f"POWER {profile['torque']} {profile['power_low']} {profile['power_high']} {profile['current']}"); time.sleep(0.6)
                self._m5_send(f"SPEED {profile['speed']} {profile['warn']}"); time.sleep(0.6)
                self._m5_send(f"RECUP {profile['neutral']} {profile['brake']}"); time.sleep(0.4)
            return
        self.log(f"=== Profiel {profile['name']} toepassen ===")
        # veilige backup vóór schrijven — altijd een herstelpunt
        try:
            self.log("Automatische backup vóór schrijven…")
            bk = self._snapshot_config()
            kph = round(bk["speed"][2] * 80 / 7250) if bk.get("speed") else "?"
            self.log(f"  Herstelpunt gemaakt (max ~{kph} km/h). Terug via Werkbon → Backup herstellen.")
        except Exception as e:
            self.log(f"  Backup overslaan ({e}) — toch doorgaan.")
        tuner = Tuner(self.sc, self.log)
        res = tuner.apply_profile(profile)
        if res == "ok":
            self.log(f"=== {profile['name']} OK ===")
            self.ui_q.put(("toast", f"Profiel {profile['name']} toegepast."))
        elif res == "login":
            self.log("=== FOUT: login mislukt (contact aan? juiste poort?) ===")
        else:
            self.log(f"=== FOUT bij stap: {res} (auto in N? niet in GO?) ===")

    def _task_apply_b64(self, b64):
        if not self._need(): return
        if self._is_m5():
            self.log("M5: SETB64 …")
            self._m5_send("SETB64 " + b64); return
        data = self._b64decode(b64)
        if len(data) < 48:
            self.log(f"Base64 te kort ({len(data)}/48)."); return
        if self._checksum(data) != data[0]:
            self.log("Base64 checksum klopt niet."); return
        def cp(i): return data[i] - 1
        p = dict(name="BASE64", drive=cp(6), neutral=cp(7), brake=cp(8),
                 speed=cp(1), warn=cp(2), torque=cp(3), power_low=cp(4),
                 power_high=cp(5), current=cp(47))
        self.log("=== Base64-profiel toepassen ===")
        tuner = Tuner(self.sc, self.log)
        res = tuner.apply_profile(p)
        self.log(f"=== {'OK' if res == 'ok' else 'FOUT:' + res} ===")

    # ---- base64 helpers ----
    @staticmethod
    def _b64decode(s):
        import base64
        try:
            return bytearray(base64.b64decode(s + "=" * (-len(s) % 4)))
        except Exception:
            return bytearray()

    @staticmethod
    def _checksum(b):
        cs = 0x0101
        for i in range(1, min(48, len(b))):
            cs += b[i]
        if (cs & 0xff) == 0:
            cs >>= 8
        return cs & 0xff

    # ================= UI-acties (main thread) =================
    def _apply_profile(self):
        if not self.connected:
            messagebox.showwarning("Twizy Pitservice", "Nog niet verbonden."); return
        p = PROFILES.get(self.profile_var.get())
        if not p: return
        if self._confirm("Profiel toepassen",
                f"Profiel '{p['name']}' naar de SEVCON schrijven?\n\n"
                "Zet de auto in N (niet GO), stilstaand, contact aan."):
            self._enqueue(lambda: self._task_apply(p))

    def _apply_b64(self):
        if not self.connected:
            messagebox.showwarning("Twizy Pitservice", "Nog niet verbonden."); return
        b64 = self.b64_var.get().strip()
        if not b64:
            messagebox.showwarning("Twizy Pitservice", "Plak eerst een base64-profiel."); return
        if self._confirm("Base64-profiel schrijven",
                "Dit profiel naar de SEVCON schrijven?\nAuto in N, stilstaand."):
            self._enqueue(lambda: self._task_apply_b64(b64))

    def _raw_cmd(self):
        if not self.connected:
            return
        line = self.raw_var.get().strip()
        self.raw_var.set("")
        if not line:
            return
        if self._is_m5():
            # M5-console accepteert de commando's rechtstreeks (CFG R/W, SPEED, TELE, …)
            self.log("> " + line)
            self._m5_send(line)
            return
        parts = line.split()
        c = parts[0].lower()
        try:
            if c == "r" and len(parts) >= 3:
                idx = int(parts[1], 16); sub = int(parts[2], 16)
                self._enqueue(lambda: self.log(f"r 0x{idx:04X}:{sub:02X} → {self.sc.read(idx, sub)}"))
            elif c == "w" and len(parts) >= 4:
                idx = int(parts[1], 16); sub = int(parts[2], 16); val = int(parts[3], 0)
                self._enqueue(lambda: self.log(f"w 0x{idx:04X}:{sub:02X}={val} → {self.sc.write(idx, sub, val)}"))
            else:
                self.log("Gebruik: r <idx> <sub>  |  w <idx> <sub> <val>  (hex idx/sub)")
        except ValueError:
            self.log("Ongeldige hex-waarde.")

    def _confirm(self, title, message):
        dlg = tk.Toplevel(self); dlg.title(title); dlg.configure(bg=PANEL); dlg.resizable(False, False)
        dlg.transient(self)
        tk.Label(dlg, text=title, bg=PANEL, fg=ACCENT, font=("Segoe UI", 12, "bold")).pack(
            padx=22, pady=(18, 4), anchor="w")
        tk.Label(dlg, text=message, bg=PANEL, fg=TXT, font=("Segoe UI", 10), justify="left",
                 wraplength=380).pack(padx=22, pady=(0, 14), anchor="w")
        res = {"ok": False}
        bar = tk.Frame(dlg, bg=PANEL); bar.pack(padx=22, pady=(0, 18), anchor="e")
        def yes(): res["ok"] = True; dlg.destroy()
        def no(): dlg.destroy()
        tk.Button(bar, text="Nee", command=no, bg=CARD, fg=TXT, relief="flat",
                  font=("Segoe UI", 10, "bold"), padx=18, pady=6, cursor="hand2").pack(side="right", padx=(8, 0))
        tk.Button(bar, text="Ja, schrijven", command=yes, bg=DANGER, fg="#fff", relief="flat",
                  font=("Segoe UI", 10, "bold"), padx=18, pady=6, cursor="hand2").pack(side="right")
        dlg.update_idletasks()
        w, h = dlg.winfo_width(), dlg.winfo_height()
        dlg.geometry(f"+{(dlg.winfo_screenwidth()-w)//2}+{(dlg.winfo_screenheight()-h)//3}")
        dlg.attributes("-topmost", True); dlg.lift(); dlg.focus_force(); dlg.grab_set()
        dlg.bind("<Return>", lambda e: yes()); dlg.bind("<Escape>", lambda e: no())
        self.wait_window(dlg)
        return res["ok"]

    # ================= UI pump =================
    def _log_console(self, s):
        try:
            import os as _os, datetime as _dt
            _lp = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "pitservice.log")
            with open(_lp, "a", encoding="utf-8") as _f:
                _f.write(f"{_dt.datetime.now():%H:%M:%S} {s}\n")
        except Exception:
            pass
        self.console.insert("end", s + "\n"); self.console.see("end")
        if int(self.console.index("end-1c").split(".")[0]) > 400:
            self.console.delete("1.0", "150.0")

    def _pump(self):
        try:
            while True:
                kind, payload = self.ui_q.get_nowait()
                if kind == "log":
                    self._log_console(payload)
                elif kind == "busy":
                    self.busy_lbl.config(text=payload)
                elif kind == "conn":
                    ok, info = payload
                    self._connecting = False
                    if ok:
                        self.status_lbl.config(text=f"● Verbonden {info}", fg=OK)
                        self.conn_btn.config(text="Verbreken", bg=DANGER, fg="#fff")
                    else:
                        self.status_lbl.config(text="● Niet verbonden" + (f" ({info})" if info else ""), fg=MUTED)
                        self.conn_btn.config(text="Verbinden", bg=OK, fg="#06121f")
                elif kind == "diagclear":
                    self.diag_txt.delete("1.0", "end")
                elif kind == "diag":
                    self.diag_txt.insert("end", payload + "\n"); self.diag_txt.see("end")
                elif kind == "ecuclear":
                    self.ecu_txt.delete("1.0", "end")
                elif kind == "ecu":
                    self.ecu_txt.insert("end", payload + "\n"); self.ecu_txt.see("end")
                elif kind == "faults":
                    if payload:
                        self.fault_lbl.config(text="⚠ Actieve faults", fg=DANGER)
                    else:
                        self.fault_lbl.config(text="✓ Geen fouten", fg=OK)
                elif kind == "live":
                    for k, lbl in self.live_vals.items():
                        if k in payload:
                            lbl.config(text=payload[k])
                elif kind == "backup":
                    bk = payload
                    kph = round(bk["speed"][2] * 80 / 7250) if bk.get("speed") else "?"
                    dr = (bk["drive"][2] // 10) if bk.get("drive") else "?"
                    import datetime as _dt
                    self.backup_lbl.config(
                        text=f"Backup {_dt.datetime.now():%H:%M:%S} — max ~{kph} km/h, rijverm {dr}%",
                        fg=OK)
                elif kind == "toast":
                    self._log_console("★ " + payload)
        except queue.Empty:
            pass
        self.after(60, self._pump)

    def _close(self):
        self.live_on = False
        self.m5_running = False
        try:
            if self.elm:
                self.elm.close()
        except Exception:
            pass
        self.destroy()


if __name__ == "__main__":
    # één-exemplaar-slot: voorkomt dat een 2e start de COM-poort van de 1e afpakt
    import socket as _socket
    _single = _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM)
    try:
        _single.bind(("127.0.0.1", 50507))
    except OSError:
        try:
            r = tk.Tk(); r.withdraw()
            messagebox.showinfo("Twizy Pitservice",
                                "De app draait al.\nGebruik het venster dat al open staat.")
        except Exception:
            pass
        import sys as _sys; _sys.exit(0)
    PitserviceGUI().mainloop()
