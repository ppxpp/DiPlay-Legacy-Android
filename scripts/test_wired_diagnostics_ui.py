#!/usr/bin/env python3
"""Accept debug diagnostic fixtures on a booted Android emulator; save XML and screenshots."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--package', default='com.shihab.diplay.hudtest')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = shutil.which('adb') or str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
    command = [adb, '-s', args.serial]
    def run(*parts, **kwargs): return subprocess.run(command+list(parts), check=True, capture_output=True, **kwargs)
    assert run('shell','getprop','sys.boot_completed').stdout.strip() == b'1', 'Emulator must finish booting first'
    args.output.mkdir(parents=True, exist_ok=True)
    scenarios = {
        'success': 'Simulated successful operation',
        'usb_denied': 'Simulated: USB permission denied',
        'ncm_missing': 'Simulated: iPhone configuration exposes no NCM interface',
        'network_failed': 'Simulated: userspace backend initialization failed',
        'airplay_timeout': 'Simulated: AirPlay session activation timed out',
        'vpn_unavailable': 'Simulated: VPN service unavailable',
    }
    for scenario, reason in scenarios.items():
        run('shell','am','force-stop',args.package)
        run('shell','logcat','-c')
        run('shell','am','start','-n',args.package+'/com.shilapi.xcertplay.diagnostics.WiredDiagnosticSimulationActivity','--es','scenario',scenario)
        end = time.monotonic()+90
        while True:
            run('shell','uiautomator','dump','/sdcard/diplay-diagnostic.xml')
            xml = run('shell','cat','/sdcard/diplay-diagnostic.xml').stdout
            tree = ET.fromstring(xml)
            texts = '\n'.join(node.get('text','') for node in tree.iter('node'))
            if reason in texts and ('SIMULATION' in texts or '模拟验收' in texts): break
            if time.monotonic() >= end: raise AssertionError(f'{scenario}: expected reason/banner not visible; {texts}')
            time.sleep(1)
        (args.output/(scenario+'.xml')).write_bytes(xml)
        (args.output/(scenario+'.png')).write_bytes(run('exec-out','screencap','-p').stdout)
        assert 'Exception' not in run('shell','logcat','-d','-s','AndroidRuntime:E').stdout.decode(errors='replace'), 'App crash in emulator log'
        print('PASS: '+scenario, flush=True)
    print('UI artifacts: '+str(args.output), flush=True)

if __name__ == '__main__': main()
