"""Device acceptance for debug CLI. Back up first; modifies only a new disabled task and AI/rules."""
import argparse
import json
from pathlib import Path
import uuid

from adb_debug import ACTION, DebugClient, encode_request, PACKAGE, RECEIVER


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    options = parser.parse_args()
    client = DebugClient(options.serial)
    outcomes = []

    def ok(command, args=None, **kw):
        response = client.command(command, args, **kw)
        assert response["ok"], response
        return response["result"]

    original_enabled = ok("status")["aiEnabled"]
    original_tasks = ok("tasks.list")
    original_rules = ok("rules.get")
    test_id = None
    try:
        ok("automation.stop")
        ok("ai.enabled", {"enabled": False})
        assert not ok("status")["aiEnabled"]
        outcomes.append("PASS status/automation.stop/ai.enabled")
        assert not client.command("engine.start")["ok"]  # No projection during this test.
        assert not client.command("frame.dump")["ok"]
        assert not client.command("ai.wake", {"text": "不得发送真实请求"})["ok"]
        assert not client.command("unknown")["ok"]
        outcomes.append("PASS missing-capture/disabled-AI/unknown-command failures")
        task = {"id": 0, "name": "回归·ADB 中文 \"quotes\" ; $()", "enabled": False,
                "steps": [{"type": "wait", "ms": 1}]}
        # Let the CLI own the fresh ID so EMUI's empty-ack fallback is safe.
        created = client.command("tasks.upsert", {"task": task})
        assert created["ok"], created
        rid = created["id"]
        test_id = created["result"]["id"]
        try:
            client.command("tasks.upsert", {"task": task}, request_id=rid)
            raise AssertionError("duplicate request unexpectedly accepted")
        except RuntimeError:
            pass
        assert len(ok("tasks.list")) == len(original_tasks) + 1
        assert client.result(rid)["result"]["id"] == test_id
        outcomes.append("PASS UTF-8/quotes/new task/request-ID replay prevention/result lookup")
        latest = ok("tasks.get", {"id": test_id})["task"]
        stale = dict(latest)
        latest["name"] += " updated"
        ok("tasks.upsert", {"task": latest})
        assert not client.command("tasks.upsert", {"task": stale})["ok"]
        ok("tasks.enabled", {"id": test_id, "enabled": True})
        ok("tasks.enabled", {"id": test_id, "enabled": False})
        assert not client.command("tasks.run", {"taskId": test_id})["ok"]
        assert ok("tasks.result", {"taskId": test_id}) is None
        outcomes.append("PASS revisions/toggle/run-rejected-while-stopped/result")
        ok("rules.set", {"rules": []})
        assert ok("rules.get") == []
        assert not client.command("rules.set", {"rules": [{"type": "bad", "timeoutMs": 10}]})["ok"]
        outcomes.append("PASS production rule validation")
        # am runs with the test APK's ordinary Linux UID, not shell. --user 0 avoids cross-user ambiguity.
        denied_id = uuid.uuid4().hex
        payload = encode_request(denied_id, "status", {})
        denied = client.run("shell", "run-as", f"{PACKAGE}.test", "am", "broadcast", "--user", "0",
                            "-a", ACTION, "-n", RECEIVER, "--es", "payload", payload).decode(errors="replace")
        assert f"FLOWCLICKER:{denied_id}" not in denied
        try:
            client.result(denied_id)
            raise AssertionError("unprivileged broadcast created a result")
        except RuntimeError:
            pass
        outcomes.append("PASS ordinary test-app UID cannot invoke debug receiver")
    finally:
        if test_id is not None:
            ok("tasks.delete", {"id": test_id})
        ok("rules.set", {"rules": original_rules})
        ok("ai.enabled", {"enabled": original_enabled})
        ok("automation.stop")
        assert ok("tasks.list") == original_tasks, "original definitions changed"
        options.evidence.write_text(json.dumps(outcomes, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n".join(outcomes))


if __name__ == "__main__":
    main()
