"""UI smoke test on an explicitly selected Android emulator; no real vehicle/phone.
Usage: python scripts/smoke_ev.py --adb /path/adb --serial emulator-5580 --output /tmp/screens
Install the current release APK first. Uses only Python's standard library.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--adb', required=True)
p.add_argument('--serial', required=True)
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
assert args.serial.startswith('emulator-'), 'This test is restricted to an emulator.'
args.output.mkdir(parents=True, exist_ok=True)
adb = [args.adb, '-s', args.serial]

def run(*command):
    return subprocess.check_output(adb + list(command), timeout=30)

def nodes():
    run('shell', 'uiautomator', 'dump', '/sdcard/openrlink-smoke.xml')
    return list(ET.fromstring(run('shell', 'cat', '/sdcard/openrlink-smoke.xml')).iter('node'))

def bounds(n):
    return list(map(int, re.findall(r'\d+', n.get('bounds'))))

def tap(text):
    for attempt in range(4):
        state = nodes()
        found = next((n for n in state if n.get('text') == text), None)
        if found is not None:
            x1, y1, x2, y2 = bounds(found)
            run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
            time.sleep(.25)
            return
        scroll = next((n for n in state if n.get('scrollable') == 'true' and bounds(n)[3]-bounds(n)[1] > 250), None)
        if scroll is None: break
        x1, y1, x2, y2 = bounds(scroll)
        run('shell', 'input', 'swipe', str((x1+x2)//2), str(y2-50), str((x1+x2)//2), str(y1+50), '300')
    raise AssertionError('Missing UI control: ' + text)

def wait_for(text, timeout=40):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        state = nodes()
        if any(text in n.get('text', '') for n in state): return state
        time.sleep(.5)
    raise AssertionError('UI did not show: ' + text)

vehicles = [
    ('zoe-ph1', 'Renault Zoe — 22 / 41 kWh (fase 1)'),
    ('zoe-ze50', 'Renault Zoe — ZE50 (fase 2)'),
    ('leaf-24-30', 'Nissan Leaf — 24 / 30 kWh'),
    ('leaf-40-62', 'Nissan Leaf — 40 / 62 kWh'),
]
for name, label in vehicles:
    run('shell', 'am', 'force-stop', 'be.technop.openrlink')
    run('shell', 'am', 'start', '-n', 'be.technop.openrlink/.MainActivity')
    wait_for('Renault Twizy')
    tap('Renault Twizy')
    tap(label)
    tap('Demo-modus')
    wait_for('DEMO — fictieve gegevens')
    tap('Accu')
    tap('Accu + 96 celspanningen')
    state = wait_for('96/96 cellen')
    assert not any(n.get('text') in ('Tuning', 'Terminal', 'Wis DTC\'s') for n in state)
    (args.output / (name+'.png')).write_bytes(run('exec-out', 'screencap', '-p'))
    if name == 'zoe-ze50':
        tap('Live')
        tap('Live starten')
        wait_for('Live-momentopname')
        tap('Live stoppen')
        wait_for('Live starten')
    print('PASS', name, 'selection, demo, 96 cells, read-only tabs', flush=True)
print('PASS live start/stop (ZE50)', flush=True)
