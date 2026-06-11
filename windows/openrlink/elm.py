"""
ELM327/STN command-engine. Eén plek voor init, CAN-headers en ISO-TP config.
De vLinker FS = STN1170 → ondersteunt zowel AT- als ST-commando's.
"""

from __future__ import annotations
from .transport import Transport


class Elm327:
    def __init__(self, transport: Transport):
        self.t = transport
        self.last_raw = ""

    def command(self, cmd: str, timeout: float = 4.0) -> str:
        """Stuurt '<cmd>\\r', leest tot prompt, strip echo + whitespace."""
        self.t.write((cmd + "\r").encode("ascii"))
        resp = self.t.read_until_prompt(timeout)
        self.last_raw = resp
        lines = [ln.strip() for ln in resp.splitlines()]
        lines = [ln for ln in lines if ln and ln.upper() != cmd.upper()]
        return " ".join(lines).strip()

    def init_obd(self) -> bool:
        """Generieke OBD-II init: 11-bit CAN @500k."""
        self.command("ATZ", 6.0)   # reset
        self.command("ATE0")       # echo uit
        self.command("ATL0")       # linefeeds uit
        self.command("ATS0")       # spaties uit
        self.command("ATH1")       # headers aan
        self.command("ATSP6")      # ISO15765 11-bit/500k
        v = self.command("ATI")
        return any(s in v.upper() for s in ("ELM", "STN", "V1", "V4"))

    def config_uds_target(self, tx_header: str, rx_header: str) -> None:
        """Gerichte UDS naar één ECU; ELM doet ISO-TP framing zelf (ATCAF1)."""
        self.command("ATSP6")
        self.command("ATCAF1")
        self.command(f"ATSH{tx_header}")
        self.command(f"ATCRA{rx_header}")
        self.command(f"ATFCSH{tx_header}")
        self.command("ATFCSD300000")
        self.command("ATFCSM1")

    def clear_filter(self) -> None:
        self.command("ATCRA")
        self.command("ATAR")

    def send_hex(self, hex_str: str, timeout: float = 5.0) -> str:
        return self.command(hex_str.replace(" ", "").upper(), timeout)
