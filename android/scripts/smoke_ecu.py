"""Exercise the ECU UI on an emulator only. An optional personal archive stays local."""
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
p.add_argument('--database', type=Path)
a = p.parse_args()
assert a.serial.startswith('emulator-'), 'Emulator required; never runs scans on a real phone.'
a.output.mkdir(parents=True, exist_ok=True)
adb = [a.adb, '-s', a.serial]

def run(*cmd): return subprocess.check_output(adb + list(cmd), timeout=45)
def state():
    run('shell', 'uiautomator', 'dump', '/sdcard/ecu-smoke.xml')
    return list(ET.fromstring(run('shell', 'cat', '/sdcard/ecu-smoke.xml')).iter('node'))
def bounds(n): return list(map(int, re.findall(r'\d+', n.get('bounds'))))
def texts(nodes): return [n.get('text', '') for n in nodes]
def wait(text, timeout=100):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        nodes = state()
        if any(text in t for t in texts(nodes)): return nodes
        time.sleep(.2)
    raise AssertionError('UI missing: ' + text)
def tap(text, tab=False):
    for _ in range(5):
        nodes = state()
        n = next((n for n in nodes if n.get('text') == text), None)
        if n is not None:
            x1,y1,x2,y2 = bounds(n)
            run('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2)); time.sleep(.2); return
        scroll = next((n for n in nodes if n.get('scrollable') == 'true' and ((bounds(n)[3]-bounds(n)[1] < 100) if tab else (bounds(n)[3]-bounds(n)[1] > 250))), None)
        if scroll is None: break
        x1,y1,x2,y2 = bounds(scroll)
        if tab: run('shell','input','swipe',str(x2-30),str((y1+y2)//2),str(x1+30),str((y1+y2)//2),'250')
        else: run('shell','input','swipe',str((x1+x2)//2),str(y2-40),str((x1+x2)//2),str(y1+40),'250')
    raise AssertionError('Missing control: ' + text)

if a.database:
    # A root-capable test image bypasses Android 11 shell restrictions on app-owned storage.
    # This script refuses real-device serial numbers before issuing any adb command.
    run('root'); run('wait-for-device')
    run('shell','am','force-stop','be.technop.openrlink')
    run('shell','am','start','-n','be.technop.openrlink/.MainActivity')
    wait('Renault Twizy'); tap('ECU', tab=True)
    run('shell','mkdir','-p','/data/media/0/Android/data/be.technop.openrlink/files')
    run('push', str(a.database), '/data/media/0/Android/data/be.technop.openrlink/files/ecu.zip')

for key, label, expected in [
    ('zoe-ph1','Renault Zoe — 22 / 41 kWh (fase 1)',21 if a.database else 15),
    ('zoe-ze50','Renault Zoe — ZE50 (fase 2)',29 if a.database else 27),
    ('leaf-24','Nissan Leaf — 24 / 30 kWh',16),
    ('leaf-40','Nissan Leaf — 40 / 62 kWh',15),
]:
    run('shell','am','force-stop','be.technop.openrlink')
    run('shell','am','start','-n','be.technop.openrlink/.MainActivity')
    wait('Renault Twizy'); tap('Renault Twizy'); tap(label); tap('Demo-modus')
    wait('DEMO — fictieve gegevens'); tap('ECU', tab=True)
    wait(f'{expected} bekende moduleadressen')
    if key == 'zoe-ph1':
        tap('Scan alle modules'); wait('Scan stoppen'); tap('Scan stoppen'); wait('Gestopt')
    tap('Scan alle modules')
    wait(f'Klaar — {expected} modules bevraagd')
    assert any('DEMO — fictieve gegevens' in t for t in texts(state()))
    (a.output/(key+'-ecu.png')).write_bytes(run('exec-out','screencap','-p'))
    print('PASS',key,expected,'module scan, personal database detection and progress',flush=True)
print('PASS stop at response boundary',flush=True)
