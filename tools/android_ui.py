"""Small ADB UI helper for the repair checklist; XML and screenshots stay local."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("action", choices=("dump", "tap", "screen"))
parser.add_argument("--serial", required=True)
parser.add_argument("--id")
parser.add_argument("--text")
parser.add_argument("--output", type=Path)
args = parser.parse_args()
adb_path = r"D:\androidsdk\platform-tools\adb.exe"


def adb(*command):
    return subprocess.check_output([adb_path, "-s", args.serial, *command])


if args.action == "screen":
    if args.output is None:
        parser.error("--output is required")
    args.output.write_bytes(adb("exec-out", "screencap", "-p"))
else:
    adb("shell", "uiautomator", "dump", "/data/local/tmp/repair-ui.xml")
    raw = adb("exec-out", "cat", "/data/local/tmp/repair-ui.xml")
    if args.output:
        args.output.write_bytes(raw)
    nodes = list(ET.fromstring(raw).iter("node"))
    if args.action == "dump":
        for node in nodes:
            a = node.attrib
            if a.get("password") == "true":
                continue
            if a.get("text") or a.get("clickable") == "true":
                print(f"{a.get('text')} | {a.get('resource-id')} | {a.get('bounds')}")
    else:
        if not args.id and not args.text:
            parser.error("tap requires --id or --text")
        matches = [node for node in nodes
                   if (not args.id or node.get("resource-id") == args.id)
                   and (not args.text or node.get("text") == args.text)]
        if len(matches) != 1:
            raise ValueError(f"expected one UI match, got {len(matches)}")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0].get("bounds")))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
