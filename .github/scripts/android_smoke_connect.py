import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: android_smoke_connect.py <window.xml>")

    xml = ET.parse(sys.argv[1])
    edits = []
    connect = None

    for node in xml.iter("node"):
        bounds = node.attrib.get("bounds", "")
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not match:
            continue
        x1, y1, x2, y2 = map(int, match.groups())
        center = ((x1 + x2) // 2, (y1 + y2) // 2)
        if node.attrib.get("class") == "android.widget.EditText":
            edits.append(center)
        if node.attrib.get("text") == "Connect to Codex":
            connect = center

    if len(edits) < 2 or connect is None:
        raise SystemExit("Connection form controls were not found")

    token = edits[1]
    adb("shell", "input", "tap", str(token[0]), str(token[1]))
    adb("shell", "input", "text", "smoketokensmoketokensmoketoken123456")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)

    adb("shell", "input", "tap", str(connect[0]), str(connect[1]))


def adb(*args):
    subprocess.run(["adb", *args], check=True)


if __name__ == "__main__":
    main()
