"""FlowClicker debug-only CLI. Stdlib only; use uv run --no-project tools/adb_debug.py."""
import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

PACKAGE = "com.flowclicker.app"
RECEIVER = f"{PACKAGE}/.debug.DebugCommandReceiver"
ACTION = f"{PACKAGE}.DEBUG_COMMAND"
COMMANDS = (
    "status", "automation.stop", "engine.start", "engine.stop", "capture.stop",
    "ai.enabled", "ai.cancel", "ai.wake", "tasks.list", "tasks.get", "tasks.upsert",
    "tasks.delete", "tasks.enabled", "tasks.run", "tasks.result", "rules.get",
    "rules.set", "screen.describe", "screen.locate", "frame.dump", "result",
)

class ResultUnavailable(RuntimeError):
    """The result file is absent or not yet readable as a complete response."""


def validate_id(request_id):
    if not re.fullmatch(r"[a-f0-9]{32}", request_id):
        raise ValueError("request ID must be 32 lowercase hex characters")
    return request_id


def encode_request(request_id, command, args):
    validate_id(request_id)
    if not isinstance(args, dict):
        raise ValueError("args must be a JSON object")
    raw = json.dumps({"id": request_id, "command": command, "args": args},
                     ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(raw) > 65536:
        raise ValueError("request exceeds 64 KiB")
    return base64.b64encode(raw).decode("ascii")


class DebugClient:
    def __init__(self, serial, adb=r"D:\androidsdk\platform-tools\adb.exe"):
        if not serial:
            raise ValueError("an explicit serial is required")
        self.serial, self.adb = serial, adb

    def run(self, *args):
        proc = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=25)
        if proc.returncode:
            # Do not include command args/payload or potential private response bodies.
            raise RuntimeError(f"adb failed ({proc.returncode}); inspect device/authorization")
        return proc.stdout

    def result(self, request_id):
        validate_id(request_id)
        try:
            value = json.loads(self.run("exec-out", "run-as", PACKAGE, "cat",
                                       f"cache/adb-debug/{request_id}.json"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            # adb exec-out may report exit 0 even when remote cat cannot find the file.
            raise ResultUnavailable(f"result unavailable or incomplete; request_id={request_id}") from error
        if not isinstance(value, dict) or value.get("id") != request_id or not isinstance(value.get("ok"), bool):
            raise ValueError("invalid or mismatched response")
        return value

    def command(self, command, args=None, request_id=None):
        fresh_id = request_id is None
        request_id = validate_id(request_id) if request_id else uuid.uuid4().hex
        payload = encode_request(request_id, command, args or {})
        print(f"request_id={request_id}", file=sys.stderr, flush=True)
        # Base64 is deliberately the only non-constant argument crossing adb's remote shell.
        # EMUI can skip even explicit broadcasts with a null action before receiver delivery.
        output = self.run("shell", "am", "broadcast", "--receiver-foreground",
                          "-a", ACTION, "-n", RECEIVER, "--es", "payload", payload).decode("utf-8", errors="replace")
        if f"FLOWCLICKER:{request_id}" not in output:
            # EMUI's background proxy may acknowledge before delivering the ordered broadcast.
            # Only our new UUID can use this fallback: an explicit ID could point at old success.
            # Never resend, and never turn an explicit receiver rejection into a cached success.
            if fresh_id and "FLOWCLICKER:" not in output:
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    try:
                        response = self.result(request_id)
                        if response.get("error") != "IN_PROGRESS_OR_INTERRUPTED":
                            return response
                    except ResultUnavailable:
                        pass
                    time.sleep(0.25)
            raise RuntimeError(f"broadcast acknowledgement unavailable; command may have executed; "
                               f"request_id={request_id}; inspect result; no automatic retry")
        return self.result(request_id)

    def download_frame(self, response, output):
        request_id = validate_id(response["id"])
        expected = f"cache/adb-debug/{request_id}.png"
        if not response["ok"] or response.get("result", {}).get("path") != expected:
            raise ValueError("response is not a frame artifact")
        data = self.run("exec-out", "run-as", PACKAGE, "cat", expected)
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ValueError("invalid PNG response")
        with Path(output).open("xb") as stream:
            stream.write(data)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=r"D:\androidsdk\platform-tools\adb.exe")
    parser.add_argument("--request-id", help="reuse only with result, not to retry a mutation")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--args", default="{}", help="JSON object; prefer --args-file for Windows quoting")
    source.add_argument("--args-file", type=Path, help="UTF-8 JSON object (not an envelope)")
    parser.add_argument("--output", type=Path, help="save frame.dump PNG; refuses to overwrite")
    parser.add_argument("command", choices=COMMANDS)
    options = parser.parse_args(argv)
    client = DebugClient(options.serial, options.adb)
    try:
        if options.command == "result":
            if not options.request_id:
                parser.error("result requires --request-id")
            response = client.result(options.request_id)
        else:
            args = json.loads(options.args_file.read_text(encoding="utf-8-sig")
                              if options.args_file else options.args)
            response = client.command(options.command, args, options.request_id)
        if options.output:
            client.download_frame(response, options.output)
        print(json.dumps(response, ensure_ascii=False, indent=2))
        return 0 if response["ok"] else 1
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"ok": False, "transportError": str(error)}, ensure_ascii=False))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
