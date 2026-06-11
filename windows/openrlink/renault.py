"""Renault/Twizy ECU-adressering (UDS over CAN). ID's = STARTPUNTEN, verifieer per ECU."""

from __future__ import annotations
from dataclasses import dataclass


@dataclass
class RenaultEcu:
    key: str
    label: str
    tx: str        # request arbitration ID
    rx: str        # response arbitration ID
    verified: bool
    note: str = ""


TWIZY: list[RenaultEcu] = [
    RenaultEcu("PEB", "PEB / Inverter (SEVCON)", "75A", "762", False,
               "rx-ID verifiëren via sniff (M5Stick / ATMA)."),
    RenaultEcu("LBC", "LBC / BMS (batterij)", "79B", "7BB", False,
               "Typische Renault-EV BMS-range; bevestigen."),
    RenaultEcu("BCB", "BCB / Charger", "792", "793", False),
    RenaultEcu("TDB", "TDB / Cluster (dashboard)", "743", "763", False),
    RenaultEcu("UCH", "UCH / Body control", "26A", "262", False,
               "UCH flagt fout vóór BMS/SEVCON-handshake — relevant voor je STOP-issue."),
]


def by_key(key: str) -> RenaultEcu | None:
    return next((e for e in TWIZY if e.key == key), None)
