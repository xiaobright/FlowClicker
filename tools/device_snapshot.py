"""Binary-safe local ADB backup/restore. Archives contain secrets: never publish them."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

PACKAGE = "com.flowclicker.app"


def inspect_archive(data):
    entries = {}
    tasks = None
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive:
            parts = Path(member.name).parts
            if not parts or parts[0] not in ("files", "shared_prefs") or ".." in parts:
                raise ValueError("unsafe archive member")
            if member.isdir():
                continue
            if not member.isfile():
                raise ValueError("unexpected link/special file in archive")
            raw = archive.extractfile(member).read()
            entries[member.name] = {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
            if member.name.endswith(".json"):
                parsed = json.loads(raw)
                if member.name == "files/tasks.json":
                    tasks = parsed
    if not isinstance(tasks, list) or len({t["id"] for t in tasks}) != len(tasks):
        raise ValueError("missing tasks array or duplicate task IDs")
    for task in tasks:
        if not isinstance(task.get("steps"), list) or not task["steps"]:
            raise ValueError("task has no steps")
    return entries, [t["id"] for t in tasks]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("backup", "restore", "verify"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--adb", default=r"D:\androidsdk\platform-tools\adb.exe")
    args = parser.parse_args()

    def adb(*command, data=None, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *command], input=data,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=check)

    archive_path = args.directory / "device.tar"
    manifest_path = args.directory / "manifest.json"
    if args.operation == "backup":
        if archive_path.exists() or manifest_path.exists():
            raise ValueError("refusing to overwrite a snapshot")
        adb("shell", "am", "force-stop", PACKAGE)
        roots = ["files"]
        if adb("shell", "run-as", PACKAGE, "ls", "-d", "shared_prefs", check=False).returncode == 0:
            roots.append("shared_prefs")
        raw = adb("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", *roots).stdout
        args.directory.mkdir(parents=True, exist_ok=True)
        archive_path.write_bytes(raw)  # Preserve bytes even if validation fails.
        entries, ids = inspect_archive(raw)
        manifest = {"serial": args.serial, "entries": entries, "task_ids": ids}
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
        print(f"BACKUP VALID: {len(entries)} files; task IDs={ids}; {archive_path.resolve()}")
        return

    raw = archive_path.read_bytes()
    entries, ids = inspect_archive(raw)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["serial"] != args.serial or manifest["entries"] != entries:
        raise ValueError("snapshot serial/hash mismatch")
    if args.operation == "restore":
        adb("shell", "am", "force-stop", PACKAGE)
        adb("exec-in", "run-as", PACKAGE, "tar", "-xf", "-", data=raw)
    # Read back each original file. Do not remove newly created files implicitly.
    for name, expected in entries.items():
        current = adb("exec-out", "run-as", PACKAGE, "cat", name).stdout
        if hashlib.sha256(current).hexdigest() != expected["sha256"]:
            raise ValueError(f"device file differs: {name}")
    print(f"{args.operation.upper()} VERIFIED: {len(entries)} original files, task IDs={ids}")


if __name__ == "__main__":
    main()
