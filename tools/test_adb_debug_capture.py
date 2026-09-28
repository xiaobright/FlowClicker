"""Read-only CLI capture acceptance. Authorize capture and foreground RepairTestActivity first."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib

from adb_debug import DebugClient


def png_dimensions(data):
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), "not PNG"
    offset = 8
    dimensions = None
    image = bytearray()
    while offset < len(data):
        size = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + size]
        crc = struct.unpack_from(">I", data, offset + 8 + size)[0]
        assert zlib.crc32(kind + payload) == crc, "PNG chunk CRC mismatch"
        if kind == b"IHDR":
            dimensions = struct.unpack_from(">II", payload)
        if kind == b"IDAT":
            image.extend(payload)
        offset += size + 12
        if kind == b"IEND":
            assert offset == len(data) and dimensions and zlib.decompress(image)
            return dimensions
    raise AssertionError("PNG is truncated")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    client = DebugClient(args.serial)
    evidence = []

    def ok(command, values=None):
        response = client.command(command, values)
        evidence.append(response)
        assert response["ok"], response
        assert client.result(response["id"]) == response, "request/result correlation lost"
        return response

    try:
        status = ok("status")["result"]
        assert status["captureRunning"] and status["frameAvailable"], "authorize capture first"
        description = ok("screen.describe")["result"]
        assert "安全点击" in description["screen_text"], "foreground the disposable fixture"
        located = ok("screen.locate", {"text": "安全点击"})["result"]["hits"]
        assert len(located) == 1
        point = located[0]["center"]
        assert 0 <= point["x"] < status["width"] and 0 <= point["y"] < status["height"]
        frame = ok("frame.dump")
        client.download_frame(frame, args.output)
        data = args.output.read_bytes()
        dimensions = png_dimensions(data)
        assert dimensions == (status["width"], status["height"])
        assert dimensions == (frame["result"]["width"], frame["result"]["height"])
        evidence.append({"pngSha256": hashlib.sha256(data).hexdigest(), "dimensions": dimensions})
        print("PASS capture status/OCR/Chinese locate/PNG CRC+decode/dimensions/request correlation")
    finally:
        args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
