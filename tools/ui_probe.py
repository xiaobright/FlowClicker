"""Small emulator-only UI probe used for visual acceptance (stdlib, no device data reset).

Examples:
  uv run --no-project tools/ui_probe.py --serial emulator-5556 --out .tools/ui-redesign home
  uv run --no-project tools/ui_probe.py --serial emulator-5556 --out .tools/ui-redesign tap --id navTasks
Only use a disposable/sanitized emulator. Screenshots and hierarchy may contain private data.
"""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


class Probe:
    def __init__(self, serial, out, adb=r"D:\androidsdk\platform-tools\adb.exe"):
        if not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("UI acceptance is restricted to an explicitly selected emulator")
        self.serial, self.out, self.adb = serial, Path(out), adb
        self.out.mkdir(parents=True, exist_ok=True)

    def run(self, *args):
        p = subprocess.run([self.adb, "-s", self.serial, *map(str, args)],
                           capture_output=True, timeout=30)
        if p.returncode:
            raise RuntimeError(p.stderr.decode(errors="replace"))
        return p.stdout

    def dump(self, name="current"):
        output = self.run("shell", "uiautomator", "dump", "/sdcard/flowclicker-ui-test.xml")
        if b"dumped to:" not in output:
            raise RuntimeError("UI hierarchy unavailable; inspect foreground/crash state before retrying")
        raw = self.run("exec-out", "cat", "/sdcard/flowclicker-ui-test.xml")
        (self.out / f"{name}.xml").write_bytes(raw)
        return ET.fromstring(raw)

    def capture(self, name):
        (self.out / f"{name}.png").write_bytes(self.run("exec-out", "screencap", "-p"))

    def tap(self, resource=None, text=None):
        tree = self.dump()
        nodes = [n for n in tree.iter("node") if
                 (resource is not None and n.get("resource-id", "").split("/")[-1] == resource) or
                 (text is not None and n.get("text") == text)]
        if len(nodes) != 1:
            raise ValueError(f"expected one visible target, got {len(nodes)}")
        node = nodes[0]
        if node.get("enabled") == "false":
            raise ValueError("target is disabled")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        if x2 <= x1 or y2 <= y1:
            raise ValueError("target has no visible bounds")
        self.run("shell", "input", "tap", (x1 + x2) // 2, (y1 + y2) // 2)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("operation", choices=("home", "dump", "tap"))
    p.add_argument("--id")
    p.add_argument("--text")
    p.add_argument("--name", default="current")
    args = p.parse_args()
    probe = Probe(args.serial, args.out)
    if args.operation == "home":
        probe.run("shell", "am", "start", "-n", "com.flowclicker.app/.MainActivity")
    elif args.operation == "tap":
        probe.tap(args.id, args.text)
    tree = probe.dump(args.name)
    probe.capture(args.name)
    for node in tree.iter("node"):
        if node.get("text") or node.get("resource-id"):
            # Never print password field values.
            text = "<password>" if node.get("password") == "true" else node.get("text")
            print(text, "|", node.get("resource-id"), "|", node.get("bounds"))


if __name__ == "__main__":
    main()
