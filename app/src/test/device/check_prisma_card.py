"""Regression for Prisma 1.3.4 (Russian UI): the button must open a card, not search.

Run against a device with configured Say and Watch and Prisma:
    python3 app/src/test/device/check_prisma_card.py SERIAL
This drives the visible UI and requires network access to the configured catalogues.
"""
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main(serial):
    adb = ['adb', '-s', serial]
    remote = '/data/local/tmp/say-and-watch-prisma-test.xml'

    def shell(*args):
        return subprocess.check_output(
            adb + ['shell', shlex.join(args)], timeout=40
        ).decode()

    def nodes():
        shell('uiautomator', 'dump', remote)
        return list(ET.fromstring(shell('cat', remote)).iter('node'))

    try:
        shell('input', 'keyevent', '224')
        shell('input', 'keyevent', '3')
        shell('am', 'start', '-W', '-n', 'com.voicesearch/.ui.SearchActivity',
              '--es', 'query', 'Матрица')
        for _ in range(8):
            ui = nodes()
            titles = [n.get('text') for n in ui if n.get('resource-id') == 'com.voicesearch:id/title']
            years = [n.get('text') for n in ui if n.get('resource-id') == 'com.voicesearch:id/year']
            if titles and titles[0] == 'Матрица' and years and years[0] == '1999':
                break
            time.sleep(1)
        else:
            raise RuntimeError('Precondition failed: Matrix (1999) is not the top result')
        button = next(n for n in ui if n.get('resource-id') == 'com.voicesearch:id/btnPrisma')
        if button.get('enabled') != 'true':
            raise RuntimeError('Precondition failed: Prisma button is disabled')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', button.get('bounds')))
        shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
        for _ in range(8):
            ui = nodes()
            text = '\n'.join(n.get('text', '') for n in ui if n.get('package') == 'top.rootu.prisma')
            if 'Торренты' in text and '1999' in text and 'Нео' in text:
                print('PASS: Say and Watch → Prisma → Matrix (1999) card')
                return
            time.sleep(1)
        raise AssertionError('Prisma did not open Matrix card; query extra can override the VIEW link')
    finally:
        shell('rm', '-f', remote)


if __name__ == '__main__':
    main(sys.argv[1])
