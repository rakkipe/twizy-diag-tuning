"""
Transportlaag voor OpenRLink-Windows.

Twee implementaties:
  - SerialTransport : vLinker FS via USB → verschijnt als COM-poort (bv. COM8),
                      net zoals je in DDT4All gebruikt. STN1170-chip, 115200 baud.
  - DemoTransport   : simuleert een ELM327, zodat je de app zonder auto kunt testen.

De M5StickC-WiFi-brug krijgt later een TcpTransport met dezelfde interface,
zodat de rest van de app niet verandert.
"""

from __future__ import annotations
import time

try:
    import serial  # pyserial
except ImportError:
    serial = None


class TransportError(Exception):
    pass


class TransportTimeout(TransportError):
    pass


class Transport:
    """Gemeenschappelijke interface. Alle implementaties leveren deze methodes."""
    name = "base"
    def open(self) -> None: ...
    def close(self) -> None: ...
    @property
    def is_open(self) -> bool: ...
    def write(self, data: bytes) -> None: ...
    def read_until_prompt(self, timeout: float = 4.0) -> str: ...


class SerialTransport(Transport):
    """vLinker FS (STN1170) over USB-COM. Blokkerend; aanroepen vanuit een worker-thread."""

    def __init__(self, port: str, baudrate: int = 115200):
        if serial is None:
            raise TransportError("pyserial niet geïnstalleerd. Run: pip install pyserial")
        self.port = port
        self.baudrate = baudrate
        self._ser = None
        self.name = f"{port}@{baudrate}"

    def open(self) -> None:
        try:
            self._ser = serial.Serial(
                self.port, self.baudrate,
                timeout=0.1, write_timeout=2.0,
            )
            # korte rust zodat de adapter klaar is
            time.sleep(0.3)
            self._ser.reset_input_buffer()
        except Exception as e:
            raise TransportError(f"Kon {self.port} niet openen: {e}") from e

    def close(self) -> None:
        try:
            if self._ser:
                self._ser.close()
        finally:
            self._ser = None

    @property
    def is_open(self) -> bool:
        return self._ser is not None and self._ser.is_open

    def write(self, data: bytes) -> None:
        if not self.is_open:
            raise TransportError("Poort niet open")
        self._ser.write(data)
        self._ser.flush()

    def read_until_prompt(self, timeout: float = 4.0) -> str:
        if not self.is_open:
            raise TransportError("Poort niet open")
        deadline = time.time() + timeout
        buf = bytearray()
        while time.time() < deadline:
            chunk = self._ser.read(256)
            if chunk:
                buf.extend(chunk)
                if b">" in buf:
                    # alles vóór de prompt teruggeven
                    return buf.split(b">")[0].decode("ascii", "ignore").strip()
            else:
                time.sleep(0.005)
        raise TransportTimeout(
            f"Timeout — partieel: {buf.decode('ascii', 'ignore').strip()!r}"
        )


class DemoTransport(Transport):
    """Simuleert een ELM327 zonder hardware (zoals AndrOBD demo-mode)."""

    name = "Demo (geen adapter)"

    def __init__(self):
        self._open = False
        self._last = ""

    def open(self) -> None:
        self._open = True

    def close(self) -> None:
        self._open = False

    @property
    def is_open(self) -> bool:
        return self._open

    def write(self, data: bytes) -> None:
        self._last = data.decode("ascii", "ignore").strip().upper()

    def read_until_prompt(self, timeout: float = 4.0) -> str:
        time.sleep(0.04)
        c = self._last
        table = {
            "0100": "41 00 BE 3F A8 13",
            "010C": "41 0C 1A F8",
            "010D": "41 0D 32",
            "0105": "41 05 5A",
            "0142": "41 42 33 9C",
            "03":   "43 02 01 33 41 01",
            "07":   "47 01 90 00",
            "0A":   "4A 00",
            "04":   "44",
        }
        if c.startswith("ATZ") or c.startswith("ATI"):
            return "ELM327 v1.5"
        if c.startswith("AT") or c.startswith("ST"):
            return "OK"
        if c in table:
            return table[c]
        if c.startswith("22"):
            return "62 " + c[2:] + " 12 34"
        if c.startswith("19"):
            return "59 02 FF E3 00 55 13"
        if c.startswith("10"):
            return "50 " + c[2:4] + " 00 32 01 F4"
        return "NO DATA"
