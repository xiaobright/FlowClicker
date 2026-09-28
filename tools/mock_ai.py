"""Loopback-only fake chat/completions provider for emulator acceptance.

No credentials are needed or logged. Use adb reverse to expose it to one emulator.
This script never operates a device itself.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--scenario", choices=["reply", "auth", "retry", "late-action", "max-rounds", "invalid-json"], default="reply")
    parser.add_argument("--delay", type=float, default=15)
    parser.add_argument("--script", help="Optional JSON array: each item has assistant, status and/or delay.")
    args = parser.parse_args()
    script = None
    if args.script:
        with open(args.script, encoding="utf-8") as stream:
            script = json.load(stream)
        if not isinstance(script, list):
            parser.error("--script must contain an array")
    lock = threading.Lock()
    calls = []
    create = {
        "tool": "upsert_task",
        "args": {"task": {"id": 0, "name": "回归·只创建一次", "trigger": {"keywords": ["__never_match__"]},
                          "steps": [{"type": "wait", "ms": 0}], "enabled": False, "mode": "debug"}},
    }

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def send_json(self, status, value):
            body = json.dumps(value, ensure_ascii=False).encode("utf-8")
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass  # Expected after the user stops an in-flight session.

        def do_GET(self):
            if self.path != "/stats":
                self.send_json(404, {"error": "not found"})
                return
            with lock:
                snapshot = list(calls)
            self.send_json(200, {"count": len(snapshot), "calls": snapshot})

        def do_POST(self):
            if not self.path.endswith("/chat/completions"):
                self.send_json(404, {"error": "not found"})
                return
            size = int(self.headers.get("Content-Length", "0"))
            if size > 20_000_000:
                self.send_json(413, {"error": "request too large"})
                return
            try:
                request = json.loads(self.rfile.read(size))
            except (ValueError, UnicodeDecodeError):
                self.send_json(400, {"error": "invalid JSON"})
                return
            messages = request.get("messages", [])
            # Only count metadata; do not persist keys, screenshots, prompts, or screen text.
            with lock:
                number = len(calls) + 1
                calls.append({"number": number, "time": time.time(), "message_count": len(messages)})
            response = {"reply": "模拟端点已收到事件；未执行额外动作。"}
            status, delay = 200, 0
            if script is not None:
                item = script[number - 1] if number <= len(script) else {}
                response, status, delay = item.get("assistant", response), item.get("status", 200), item.get("delay", 0)
            elif args.scenario == "auth":
                status = 401
            elif args.scenario == "retry":
                if number == 1:
                    response = create
                elif number == 2:
                    status = 503
            elif args.scenario == "late-action" and number == 1:
                delay, response = args.delay, create
            elif args.scenario == "max-rounds":
                response = {"tool": "log_note", "args": {"text": f"模拟工具第{number}次"}}
            elif args.scenario == "invalid-json":
                response = "not a JSON protocol response"
            if delay:
                time.sleep(delay)
            with lock:
                calls[number - 1]["status"] = status
            if status != 200:
                self.send_json(status, {"error": f"synthetic HTTP {status}"})
            else:
                content = response if isinstance(response, str) else json.dumps(response, ensure_ascii=False)
                self.send_json(200, {"choices": [{"message": {"role": "assistant", "content": content}}]})

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Fake AI listening on http://127.0.0.1:{args.port}/v1, scenario={args.scenario}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
