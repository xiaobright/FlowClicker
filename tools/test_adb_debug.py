import base64
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from adb_debug import ACTION, RECEIVER, DebugClient, encode_request, validate_id

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

    def test_broadcast_has_action_and_explicit_receiver_for_emui(self):
        client = DebugClient("test")
        response = {"id": ID, "ok": True, "result": {}}
        with patch.object(client, "run", side_effect=[
            f'Broadcast completed: result=0, data="FLOWCLICKER:{ID}"'.encode(),
            json.dumps(response).encode(),
        ]) as call:
            self.assertEqual(client.command("status", request_id=ID), response)
            self.assertEqual(call.call_args_list[0].args, (
                "shell", "am", "broadcast", "--receiver-foreground",
                "-a", ACTION, "-n", RECEIVER, "--es", "payload",
                encode_request(ID, "status", {}),
            ))
            self.assertEqual(call.call_count, 2)

    def test_duplicate_id_does_not_read_old_success_or_retry(self):
        client = DebugClient("test")
        with patch.object(client, "run", return_value=
                          f'Broadcast completed: result=2, data="FLOWCLICKER:ID_USED:{ID}"'.encode()) as call:
            with self.assertRaises(RuntimeError):
                client.command("status", request_id=ID)
            self.assertEqual(call.call_count, 1)

    def test_fresh_id_missing_ack_reads_same_result_without_resending(self):
        client = DebugClient("test")
        response = {"id": ID, "ok": True, "result": {"accepted": True}}
        with patch("adb_debug.uuid.uuid4") as new_id, patch.object(client, "run", side_effect=[
            b"Broadcast completed: result=0", json.dumps(response).encode(),
        ]) as call:
            new_id.return_value.hex = ID
            self.assertEqual(client.command("automation.stop"), response)
            self.assertEqual(call.call_count, 2)
            self.assertEqual(call.call_args_list[1].args,
                             ("exec-out", "run-as", "com.flowclicker.app", "cat", f"cache/adb-debug/{ID}.json"))

    def test_missing_and_pending_results_are_polled_not_rebroadcast(self):
        client = DebugClient("test")
        pending = {"id": ID, "ok": False, "error": "IN_PROGRESS_OR_INTERRUPTED"}
        response = {"id": ID, "ok": False, "error": "no valid capture frame"}
        with patch("adb_debug.uuid.uuid4") as new_id, patch("adb_debug.time.sleep"), \
                patch.object(client, "run", side_effect=[
                    b"Broadcast completed: result=0", b"cat: No such file or directory",
                    json.dumps(pending).encode(), json.dumps(response).encode(),
                ]) as call:
            new_id.return_value.hex = ID
            self.assertEqual(client.command("frame.dump"), response)
            self.assertEqual(call.call_count, 4)
            self.assertTrue(all(c.args[0] == "exec-out" for c in call.call_args_list[1:]))

    def test_missing_ack_timeout_never_resends(self):
        client = DebugClient("test")
        with patch("adb_debug.uuid.uuid4") as new_id, \
                patch("adb_debug.time.monotonic", side_effect=[0, 0, 11]), \
                patch("adb_debug.time.sleep"), patch.object(client, "run", side_effect=[
                    b"Broadcast completed: result=0", b"cat: No such file or directory",
                ]) as call:
            new_id.return_value.hex = ID
            with self.assertRaisesRegex(RuntimeError, "command may have executed"):
                client.command("automation.stop")
            self.assertEqual(call.call_count, 2)

    def test_fresh_id_explicit_rejection_does_not_fall_back(self):
        client = DebugClient("test")
        with patch("adb_debug.uuid.uuid4") as new_id, patch.object(client, "run", return_value=
                          f'Broadcast completed: result=2, data="FLOWCLICKER:ID_USED:{ID}"'.encode()) as call:
            new_id.return_value.hex = ID
            with self.assertRaises(RuntimeError):
                client.command("status")
            self.assertEqual(call.call_count, 1)

    def test_fallback_rejects_mismatched_or_non_object_response(self):
        for raw in (b'{"id":"wrong","ok":true}', b'[]'):
            with self.subTest(raw=raw), patch("adb_debug.uuid.uuid4") as new_id:
                new_id.return_value.hex = ID
                client = DebugClient("test")
                with patch.object(client, "run", side_effect=[b"Broadcast completed: result=0", raw]) as call:
                    with self.assertRaises(ValueError):
                        client.command("status")
                    self.assertEqual(call.call_count, 2)

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
