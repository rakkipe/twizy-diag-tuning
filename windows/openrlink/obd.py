"""Generieke OBD-II: DTC-decoder (mode 03/07/0A), live PID's (mode 01)."""

from __future__ import annotations
from dataclasses import dataclass
from typing import Callable, Optional

_SYS = "PCBU"


def decode_dtcs(hex_response: str) -> list[str]:
    """
    2 bytes per DTC; bovenste 2 bits van byte1 = systeem (P/C/B/U).

    Respons-vorm: <mode> [count] <dtc-paren...>
    Bv. '43 02 01 33 41 01' = mode 0x43, count 2, dan 0133 + 4101.
    De count-byte is aanwezig wanneer (len ná mode) oneven is → dan ook overslaan,
    zodat enkel complete 2-byte DTC's resten.
    """
    tokens = hex_response.replace("\n", " ").split()
    by = [int(t, 16) for t in tokens if len(t) == 2 and _is_hex(t)]
    if not by:
        return []
    payload = by[1:]  # skip mode-respons byte (0x43/0x47/0x4A)
    if len(payload) % 2 == 1:
        payload = payload[1:]  # skip count-byte
    out: list[str] = []
    i = 0
    while i + 1 < len(payload):
        b1, b2 = payload[i], payload[i + 1]
        i += 2
        if b1 == 0 and b2 == 0:
            continue
        letter = _SYS[(b1 >> 6) & 0x03]
        out.append(f"{letter}{(b1 >> 4) & 0x03}{b1 & 0x0F:X}{(b2 >> 4) & 0x0F:X}{b2 & 0x0F:X}")
    return out


def _is_hex(s: str) -> bool:
    try:
        int(s, 16); return True
    except ValueError:
        return False


@dataclass
class Pid:
    mode: str
    pid: str
    label: str
    unit: str
    decode: Callable[[list[int]], float]

    @property
    def command(self) -> str:
        return self.mode + self.pid


PIDS: list[Pid] = [
    Pid("01", "0C", "Toerental", "rpm", lambda d: (d[0] * 256 + d[1]) / 4.0),
    Pid("01", "0D", "Snelheid", "km/h", lambda d: float(d[0])),
    Pid("01", "05", "Koelvloeistof", "°C", lambda d: float(d[0] - 40)),
    Pid("01", "0F", "Inlaatlucht", "°C", lambda d: float(d[0] - 40)),
    Pid("01", "11", "Gasklep", "%", lambda d: d[0] * 100.0 / 255.0),
    Pid("01", "2F", "Brandstof", "%", lambda d: d[0] * 100.0 / 255.0),
    Pid("01", "42", "Module-spanning", "V", lambda d: (d[0] * 256 + d[1]) / 1000.0),
    Pid("01", "04", "Motorbelasting", "%", lambda d: d[0] * 100.0 / 255.0),
]


def parse_payload(pid: Pid, raw: str) -> Optional[list[int]]:
    by = [int(t, 16) for t in raw.split() if len(t) == 2 and _is_hex(t)]
    expect_mode = 0x40 + int(pid.mode, 16)
    pid_byte = int(pid.pid, 16)
    if expect_mode not in by:
        return None
    idx = by.index(expect_mode)
    if idx + 1 >= len(by) or by[idx + 1] != pid_byte:
        return None
    return by[idx + 2:]


@dataclass
class LiveValue:
    label: str
    value: float
    unit: str


class ObdService:
    def __init__(self, elm):
        self.elm = elm

    def read_stored_dtcs(self) -> list[str]:
        return decode_dtcs(self.elm.command("03"))

    def read_pending_dtcs(self) -> list[str]:
        return decode_dtcs(self.elm.command("07"))

    def read_permanent_dtcs(self) -> list[str]:
        return decode_dtcs(self.elm.command("0A"))

    def clear_dtcs(self) -> bool:
        return "44" in self.elm.command("04").replace(" ", "")

    def read_pid(self, pid: Pid) -> Optional[LiveValue]:
        raw = self.elm.command(pid.command, 2.5)
        payload = parse_payload(pid, raw)
        if payload is None:
            return None
        try:
            return LiveValue(pid.label, pid.decode(payload), pid.unit)
        except Exception:
            return None

    def snapshot(self) -> list[LiveValue]:
        return [v for v in (self.read_pid(p) for p in PIDS) if v]
