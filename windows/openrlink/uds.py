"""UDS (ISO 14229) over ISO-TP. ELM doet de framing; wij sturen kale service-PDU's."""

from __future__ import annotations
from dataclasses import dataclass, field


_NRC = {
    0x10: "generalReject", 0x11: "serviceNotSupported",
    0x12: "subFunctionNotSupported", 0x13: "incorrectMessageLength",
    0x22: "conditionsNotCorrect", 0x24: "requestSequenceError",
    0x31: "requestOutOfRange", 0x33: "securityAccessDenied",
    0x35: "invalidKey", 0x78: "responsePending(busy)",
    0x7E: "subFnNotSupportedInActiveSession",
    0x7F: "serviceNotSupportedInActiveSession",
}


def nrc_text(code: int) -> str:
    return f"0x{code:02X} {_NRC.get(code, 'onbekend')}"


@dataclass
class UdsResult:
    ok: bool
    data: list[int] = field(default_factory=list)
    raw: str = ""
    nrc: str | None = None


class UdsClient:
    def __init__(self, elm):
        self.elm = elm

    def _send(self, pdu: str) -> UdsResult:
        raw = self.elm.send_hex(pdu)
        by = [int(t, 16) for t in raw.split() if len(t) == 2 and _hex_ok(t)]
        if not by or "NO DATA" in raw or "ERROR" in raw:
            return UdsResult(False, [], raw, "Geen/ongeldige respons")
        if by[0] == 0x7F and len(by) >= 3:
            return UdsResult(False, by, raw, nrc_text(by[2]))
        return UdsResult(True, by, raw)

    def diagnostic_session(self, sub: int) -> UdsResult:
        return self._send(f"10{sub:02X}")

    def read_data_by_id(self, did: int) -> UdsResult:
        return self._send(f"22{did:04X}")

    def write_data_by_id(self, did: int, data_hex: str) -> UdsResult:
        return self._send(f"2E{did:04X}" + data_hex.replace(" ", ""))

    def read_dtc_info(self, sub: int, mask: int = 0xFF) -> UdsResult:
        return self._send(f"19{sub:02X}{mask:02X}")

    def clear_diagnostic_info(self, group: int = 0xFFFFFF) -> UdsResult:
        return self._send(f"14{group:06X}")

    def routine_control(self, rtype: int, rid: int, param_hex: str = "") -> UdsResult:
        return self._send(f"31{rtype:02X}{rid:04X}" + param_hex.replace(" ", ""))

    def tester_present(self) -> UdsResult:
        return self._send("3E00")


def _hex_ok(s: str) -> bool:
    try:
        int(s, 16); return True
    except ValueError:
        return False
