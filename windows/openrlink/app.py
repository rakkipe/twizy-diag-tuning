"""
OpenRLink — Windows 11 desktop-app (Tkinter).
Diagnose voor Renault Twizy via vLinker FS (USB-COM) of demo-modus.

Start:  python -m openrlink.app    (of: python app.py)
"""

from __future__ import annotations
import queue
import threading
import tkinter as tk
from tkinter import ttk, messagebox

from .transport import SerialTransport, DemoTransport, TransportError
from .elm import Elm327
from .obd import ObdService
from .uds import UdsClient
from .sevcon import Sevcon, REGISTERS
from . import renault

try:
    from serial.tools import list_ports
except ImportError:
    list_ports = None


class Worker(threading.Thread):
    """Voert blokkerende adapter-taken uit; resultaten via een queue naar de UI."""
    def __init__(self, fn, on_done):
        super().__init__(daemon=True)
        self.fn = fn
        self.on_done = on_done

    def run(self):
        try:
            result = self.fn()
            self.on_done(("ok", result))
        except Exception as e:  # noqa
            self.on_done(("err", str(e)))


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("OpenRLink — Twizy diagnose")
        self.geometry("760x560")
        self.minsize(680, 480)

        self.elm: Elm327 | None = None
        self.obd: ObdService | None = None
        self.transport = None
        self.connected = False
        self._ui_queue: queue.Queue = queue.Queue()

        self._build_ui()
        self.after(80, self._drain_queue)

    # ---------- UI opbouw ----------
    def _build_ui(self):
        top = ttk.Frame(self, padding=8)
        top.pack(fill="x")

        ttk.Label(top, text="COM-poort:").pack(side="left")
        self.port_var = tk.StringVar()
        self.port_combo = ttk.Combobox(top, textvariable=self.port_var, width=22, state="readonly")
        self.port_combo.pack(side="left", padx=4)
        ttk.Button(top, text="Ververs", command=self._refresh_ports).pack(side="left")
        ttk.Label(top, text="Baud:").pack(side="left", padx=(10, 2))
        self.baud_var = tk.StringVar(value="115200")
        ttk.Combobox(top, textvariable=self.baud_var, width=8,
                     values=["38400", "115200", "500000"], state="readonly").pack(side="left")

        self.connect_btn = ttk.Button(top, text="Verbind", command=self._toggle_connect)
        self.connect_btn.pack(side="left", padx=8)
        ttk.Button(top, text="Demo", command=self._connect_demo).pack(side="left")

        self.status_var = tk.StringVar(value="Niet verbonden")
        ttk.Label(self, textvariable=self.status_var, foreground="#a06000").pack(fill="x", padx=8)

        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=8, pady=4)

        self.tab_obd = ttk.Frame(nb); nb.add(self.tab_obd, text="OBD")
        self.tab_twizy = ttk.Frame(nb); nb.add(self.tab_twizy, text="Twizy / SEVCON")
        self.tab_term = ttk.Frame(nb); nb.add(self.tab_term, text="Terminal")
        self._build_obd_tab()
        self._build_twizy_tab()
        self._build_term_tab()

        # log onderaan
        ttk.Label(self, text="Log:").pack(anchor="w", padx=8)
        self.log = tk.Text(self, height=7, font=("Consolas", 9))
        self.log.pack(fill="x", padx=8, pady=(0, 8))

        self._refresh_ports()

    def _build_obd_tab(self):
        f = self.tab_obd
        bar = ttk.Frame(f, padding=6); bar.pack(fill="x")
        ttk.Button(bar, text="Scan DTC's", command=self._scan_dtcs).pack(side="left", padx=3)
        ttk.Button(bar, text="Wis DTC's", command=self._clear_dtcs).pack(side="left", padx=3)
        ttk.Button(bar, text="Live data", command=self._read_live).pack(side="left", padx=3)
        self.obd_out = tk.Text(f, font=("Consolas", 10))
        self.obd_out.pack(fill="both", expand=True, padx=6, pady=6)

    def _build_twizy_tab(self):
        f = self.tab_twizy
        warn = ("LET OP: SEVCON-login + LEZEN is veilig. SCHRIJVEN is expert-only — "
                "een fout register herhaalt de PMAP-corruptie (0x4611/0x4610).")
        ttk.Label(f, text=warn, foreground="#c00000", wraplength=720).pack(anchor="w", padx=6, pady=4)

        bar = ttk.Frame(f); bar.pack(fill="x", padx=6)
        ttk.Button(bar, text="SEVCON login + level lezen", command=self._sevcon_login).pack(side="left", padx=3)
        ttk.Button(bar, text="Alle leesbare registers", command=self._sevcon_read_all).pack(side="left", padx=3)

        ecubar = ttk.Frame(f); ecubar.pack(fill="x", padx=6, pady=4)
        ttk.Label(ecubar, text="ECU-DTC's (UDS 0x19):").pack(side="left")
        for ecu in renault.TWIZY:
            ttk.Button(ecubar, text=ecu.key,
                       command=lambda e=ecu: self._ecu_dtcs(e)).pack(side="left", padx=2)

        self.twizy_out = tk.Text(f, font=("Consolas", 10))
        self.twizy_out.pack(fill="both", expand=True, padx=6, pady=6)

    def _build_term_tab(self):
        f = self.tab_term
        bar = ttk.Frame(f, padding=6); bar.pack(fill="x")
        self.term_in = ttk.Entry(bar)
        self.term_in.pack(side="left", fill="x", expand=True)
        self.term_in.bind("<Return>", lambda e: self._send_raw())
        ttk.Button(bar, text="Stuur", command=self._send_raw).pack(side="left", padx=4)
        for q in ("ATZ", "ATI", "0100", "03", "ATMA"):
            ttk.Button(bar, text=q, command=lambda c=q: self._send_raw(c)).pack(side="left", padx=1)
        self.term_out = tk.Text(f, font=("Consolas", 10))
        self.term_out.pack(fill="both", expand=True, padx=6, pady=6)

    # ---------- helpers ----------
    def _log(self, msg: str):
        self.log.insert("1.0", msg + "\n")

    def _drain_queue(self):
        try:
            while True:
                fn = self._ui_queue.get_nowait()
                fn()
        except queue.Empty:
            pass
        self.after(80, self._drain_queue)

    def _run_bg(self, fn, done):
        """Start een achtergrondtaak; 'done' draait op de UI-thread."""
        def on_done(result):
            self._ui_queue.put(lambda: done(result))
        Worker(fn, on_done).start()

    def _refresh_ports(self):
        ports = []
        if list_ports:
            ports = [f"{p.device} — {p.description}" for p in list_ports.comports()]
        self.port_combo["values"] = ports
        if ports and not self.port_var.get():
            self.port_var.set(ports[0])

    # ---------- verbinden ----------
    def _toggle_connect(self):
        if self.connected:
            self._disconnect()
            return
        sel = self.port_var.get()
        if not sel:
            messagebox.showwarning("Geen poort", "Selecteer een COM-poort (of gebruik Demo).")
            return
        port = sel.split(" — ")[0]
        baud = int(self.baud_var.get())
        self._do_connect(SerialTransport(port, baud))

    def _connect_demo(self):
        self._do_connect(DemoTransport())

    def _do_connect(self, transport):
        self.status_var.set(f"Verbinden met {transport.name}…")
        def task():
            transport.open()
            elm = Elm327(transport)
            if not elm.init_obd():
                raise TransportError("Adapter reageerde niet als ELM/STN")
            return transport, elm
        def done(result):
            kind, val = result
            if kind == "err":
                self.status_var.set(f"Fout: {val}")
                self._log("✗ " + val)
                return
            self.transport, self.elm = val
            self.obd = ObdService(self.elm)
            self.connected = True
            self.connect_btn.config(text="Verbreek")
            self.status_var.set(f"Verbonden — {self.transport.name}")
            self._log("✓ Verbonden met " + self.transport.name)
        self._run_bg(task, done)

    def _disconnect(self):
        if self.transport:
            self.transport.close()
        self.connected = False
        self.connect_btn.config(text="Verbind")
        self.status_var.set("Niet verbonden")
        self._log("Verbinding gesloten")

    def _guard(self) -> bool:
        if not self.connected:
            messagebox.showinfo("Niet verbonden", "Verbind eerst met de adapter of Demo.")
            return False
        return True

    # ---------- OBD ----------
    def _scan_dtcs(self):
        if not self._guard(): return
        def task():
            return self.obd.read_stored_dtcs(), self.obd.read_pending_dtcs()
        def done(r):
            if r[0] == "err": return self._log("✗ " + r[1])
            stored, pending = r[1]
            self.obd_out.delete("1.0", "end")
            self.obd_out.insert("end", f"Opgeslagen DTC's ({len(stored)}):\n")
            self.obd_out.insert("end", "  " + (", ".join(stored) or "—") + "\n\n")
            self.obd_out.insert("end", f"Pending DTC's ({len(pending)}):\n")
            self.obd_out.insert("end", "  " + (", ".join(pending) or "—") + "\n")
            self._log(f"DTC-scan: {len(stored)} opgeslagen, {len(pending)} pending")
        self._run_bg(task, done)

    def _clear_dtcs(self):
        if not self._guard(): return
        if not messagebox.askyesno("Bevestig", "DTC's en MIL wissen?"):
            return
        def done(r):
            if r[0] == "err": return self._log("✗ " + r[1])
            ok = r[1]
            self._log("✓ DTC's gewist" if ok else "✗ Wissen niet bevestigd")
        self._run_bg(lambda: self.obd.clear_dtcs(), done)

    def _read_live(self):
        if not self._guard(): return
        def done(r):
            if r[0] == "err": return self._log("✗ " + r[1])
            snap = r[1]
            self.obd_out.delete("1.0", "end")
            for v in snap:
                self.obd_out.insert("end", f"{v.label:18s}: {v.value:8.1f} {v.unit}\n")
            self._log(f"Live: {len(snap)} PID's gelezen")
        self._run_bg(lambda: self.obd.snapshot(), done)

    # ---------- Twizy / SEVCON ----------
    def _sevcon_login(self):
        if not self._guard(): return
        def done(r):
            if r[0] == "err": return self._log("✗ " + r[1])
            ok = r[1]
            self.twizy_out.insert("end", f"SEVCON login: {'OK (level 4)' if ok else 'MISLUKT'}\n")
            self._log("SEVCON login " + ("OK" if ok else "mislukt"))
        self._run_bg(lambda: Sevcon(self.elm).login(), done)

    def _sevcon_read_all(self):
        if not self._guard(): return
        def task():
            sc = Sevcon(self.elm)
            out = []
            for reg in REGISTERS:
                val = sc.read(reg.index, reg.sub)
                out.append((reg, val))
            return out
        def done(r):
            if r[0] == "err": return self._log("✗ " + r[1])
            self.twizy_out.delete("1.0", "end")
            for reg, val in r[1]:
                shown = "abort/leeg" if val is None else f"{val} (0x{val:X})"
                flag = "" if reg.writable else "  [read-only aanbevolen]"
                self.twizy_out.insert("end", f"0x{reg.index:04X}.{reg.sub:02X} {reg.label:34s}: {shown}{flag}\n")
            self._log("SEVCON registers gelezen")
        self._run_bg(task, done)

    def _ecu_dtcs(self, ecu):
        if not self._guard(): return
        def task():
            self.elm.config_uds_target(ecu.tx, ecu.rx)
            uds = UdsClient(self.elm)
            uds.diagnostic_session(0x03)
            uds.tester_present()
            return uds.read_dtc_info(0x02, 0xFF)
        def done(r):
            if r[0] == "err": return self._log("✗ " + r[1])
            res = r[1]
            txt = res.raw if res.ok else f"NRC: {res.nrc}"
            self.twizy_out.insert("end", f"[{ecu.key}] {'OK' if res.ok else 'fout'} → {txt}\n")
            if not ecu.verified:
                self.twizy_out.insert("end", f"   (ID {ecu.tx}/{ecu.rx} nog te verifiëren)\n")
            self._log(f"{ecu.key} DTC-request klaar")
        self._run_bg(task, done)

    # ---------- Terminal ----------
    def _send_raw(self, preset: str | None = None):
        if not self._guard(): return
        cmd = preset or self.term_in.get().strip()
        if not cmd: return
        if not preset:
            self.term_in.delete(0, "end")
        def done(r):
            out = r[1] if r[0] == "ok" else "FOUT: " + r[1]
            self.term_out.insert("1.0", f"> {cmd}\n{out}\n\n")
        self._run_bg(lambda: self.elm.command(cmd), done)


def main():
    App().mainloop()


if __name__ == "__main__":
    main()
