"""Regression: leaving the scrolled feed for the history or LazyMedia button must not shift it.

The feed scrolled by a column, focus on its right edge; UP to the history button
(or DOWN to the app buttons) moved the whole grid back by one column.

Run against a device with configured Say and Watch and the trending feed loaded:
    python3 app/src/test/device/check_feed_focus_exit.py SERIAL
This drives the visible UI and requires network access to TMDB.
"""
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main(serial):
    adb = ['adb', '-s', serial]
    remote = '/data/local/tmp/say-and-watch-feed-test.xml'

    def shell(*args):
        return subprocess.check_output(
            adb + ['shell', shlex.join(args)], timeout=40
        ).decode()

    def key(name, times=1):
        for _ in range(times):
            shell('input', 'keyevent', f'KEYCODE_DPAD_{name}')
            time.sleep(0.6)
        time.sleep(1.5)

    def state():
        """(left column titles, focused card row or None, focused view id)."""
        shell('uiautomator', 'dump', remote)
        ui = list(ET.fromstring(shell('cat', remote)).iter('node'))
        cards = []
        for n in ui:
            if n.get('resource-id') == 'com.voicesearch:id/title':
                x, y = map(int, re.findall(r'\d+', n.get('bounds', ''))[:2])
                cards.append((x, y, n.get('text'), n.get('selected') == 'true'))
        left = min(x for x, _, _, _ in cards)
        column = tuple(t for x, _, t, _ in sorted(cards, key=lambda c: c[1]) if x == left)
        top = min(y for _, y, _, _ in cards)
        row = next(((0 if y == top else 1) for _, y, _, s in cards if s), None)
        focused = next((n.get('resource-id') for n in ui if n.get('focused') == 'true'), None)
        return column, row, focused

    def check(direction, expected_focus):
        shell('am', 'force-stop', 'com.voicesearch')
        shell('am', 'start', '-W', '-n', 'com.voicesearch/.ui.SearchActivity')
        time.sleep(6)
        start, _, _ = state()
        key('DOWN')
        key('RIGHT', 14)
        column, row, _ = state()
        if column == start:
            raise RuntimeError('Precondition failed: the feed did not scroll')
        edge_row = 0 if direction == 'UP' else 1
        if row is None:
            raise RuntimeError('Precondition failed: no card in focus')
        if row != edge_row:
            key(direction)
            column, row, _ = state()
            if row != edge_row:
                raise RuntimeError(f'Precondition failed: focus is not on row {edge_row}')
        key(direction)
        after, row, focused = state()
        if focused != expected_focus:
            raise RuntimeError(f'Precondition failed: focus went to {focused}')
        if after != column:
            raise AssertionError(f'{direction}: feed shifted, left column {column} -> {after}')
        print(f'PASS: {direction} to {expected_focus.split("/")[-1]} keeps left column {column}')

    try:
        check('UP', 'com.voicesearch:id/historyButton')
        check('DOWN', 'com.voicesearch:id/btnLazyMedia')
    finally:
        shell('rm', '-f', remote)


if __name__ == '__main__':
    main(sys.argv[1])
