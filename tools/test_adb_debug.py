import base64
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from adb_debug import DebugClient, encode_request, validate_id

ID = "0123456789abcdef0123456789abcdef"


class DebugClientTests(unittest.TestCase):
    def test_chinese_quotes_and_shell_characters_are_encoded(self):
        text = '中文 \' " ; $(cmd)\nnext'
        payload = encode_request(ID, "ai.wake", {"text": text})
        self.assertEqual(json.loads(base64.b64decode(payload))["args"]["text"], text)
        self.assertNotIn(";", payload)

    def test_invalid_id_and_non_object_are_rejected(self):
        for invalid in ("../files", "/tmp/a", "A" * 32, ""):
            with self.assertRaises(ValueError):
                validate_id(invalid)
        with self.assertRaises(ValueError):
            encode_request(ID, "status", [])

    def test_limit_applies_to_utf8_bytes(self):
        with self.assertRaises(ValueError):
            encode_request(ID, "ai.wake", {"text": "汉" * 23000})

    def test_mismatched_result_rejected(self):
        client = DebugClient("test")
        with patch.object(client, "run", return_value=b'{"id":"wrong","ok":true}'):
            with self.assertRaises(ValueError):
                client.result(ID)

    def test_exec_out_missing_file_is_not_a_successful_result(self):
        client = DebugClient("test")
        with patch.object(client, "run", return_value=b"cat: no such file or directory\n"):
            with self.assertRaises(RuntimeError):
                client.result(ID)

    def test_permission_failure_does_not_read_stale_result_or_retry(self):
        client = DebugClient("test")
        with patch.object(client, "run", return_value=b"Broadcast completed: result=0") as call:
            with self.assertRaises(RuntimeError):
                client.command("status", request_id=ID)
            self.assertEqual(call.call_count, 1)

    def test_frame_path_is_fixed_not_server_selected(self):
        client = DebugClient("test")
        with self.assertRaises(ValueError):
            client.download_frame({"id": ID, "ok": True, "result": {"path": "files/ai_settings.json"}}, "unused")

    def test_output_refuses_overwrite(self):
        client = DebugClient("test")
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "frame.png"
            target.write_bytes(b"original")
            response = {"id": ID, "ok": True, "result": {"path": f"cache/adb-debug/{ID}.png"}}
            with patch.object(client, "run", return_value=b"\x89PNG\r\n\x1a\nfake"):
                with self.assertRaises(FileExistsError):
                    client.download_frame(response, target)
            self.assertEqual(target.read_bytes(), b"original")


if __name__ == "__main__":
    unittest.main()
