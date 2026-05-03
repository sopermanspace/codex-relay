import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: android_smoke_connect.py <window.xml>")

    xml_path = sys.argv[1]
    edits, connect = find_connection_controls(xml_path)

    for _ in range(4):
        if len(edits) >= 2 and connect is not None:
            break
        if tap_text(xml_path, "Wait"):
            time.sleep(3)
            dump_window(xml_path)
            edits, connect = find_connection_controls(xml_path)
            continue
        time.sleep(2)
        dump_window(xml_path)
        edits, connect = find_connection_controls(xml_path)

    if len(edits) < 2 or connect is None:
        raise SystemExit("Connection form controls were not found")

    token = edits[1]
    adb("shell", "input", "tap", str(token[0]), str(token[1]))
    adb("shell", "input", "text", "smoketokensmoketokensmoketoken123456")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)

    adb("shell", "input", "tap", str(connect[0]), str(connect[1]))


def find_connection_controls(xml_path):
    xml = ET.parse(xml_path)
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

    return edits, connect


def tap_text(xml_path, label):
    xml = ET.parse(xml_path)
    for node in xml.iter("node"):
        if node.attrib.get("text") != label:
            continue
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if not match:
            return False
        x1, y1, x2, y2 = map(int, match.groups())
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        return True
    return False


def dump_window(xml_path):
    adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
    with open(xml_path, "wb") as output:
        subprocess.run(["adb", "exec-out", "cat", "/sdcard/window.xml"], check=True, stdout=output)


def adb(*args):
    subprocess.run(["adb", *args], check=True)


if __name__ == "__main__":
    main()
