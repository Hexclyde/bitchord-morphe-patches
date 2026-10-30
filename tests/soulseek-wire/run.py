#!/usr/bin/env python3
"""Exercise the JNI library against local fixtures and signaling input checks.

No real Soulseek/Telegram login, credentials, public server or audio is used.
This complements the native Rust peer-transfer tests; it does not replace device QA.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[2]


def uint(value: int) -> bytes:
    return struct.pack("<I", value)


def string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return uint(len(encoded)) + encoded


def frame(code: int, body: bytes) -> bytes:
    payload = uint(code) + body
    return uint(len(payload)) + payload


def exact(connection: socket.socket, count: int) -> bytes:
    data = bytearray()
    while len(data) < count:
        part = connection.recv(count - len(data))
        if not part:
            raise EOFError("connection closed")
        data.extend(part)
    return bytes(data)


def take_string(data: bytes, offset: int = 0) -> tuple[str, int]:
    if offset + 4 > len(data):
        raise AssertionError("truncated fixture string length")
    size = struct.unpack_from("<I", data, offset)[0]
    end = offset + 4 + size
    if end > len(data):
        raise AssertionError("truncated fixture string")
    return data[offset + 4 : end].decode("utf-8"), end


class Server:
    """Understands just enough Soulseek server messages for JNI boundary checks."""

    def __init__(self, reject: bool = False):
        self.reject = reject
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.socket.bind(("127.0.0.1", 0))
        self.socket.listen(4)
        self.socket.settimeout(0.2)
        self.port = self.socket.getsockname()[1]
        self.stop = threading.Event()
        self.login_seen = threading.Event()
        self.searches: list[str] = []
        self.errors: list[Exception] = []
        self.connections: list[socket.socket] = []
        self.workers: list[threading.Thread] = []
        self.thread = threading.Thread(target=self.accept, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.socket.close()
        for connection in self.connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self.thread.join(timeout=2)
        for worker in self.workers:
            worker.join(timeout=2)

    def accept(self):
        while not self.stop.is_set():
            try:
                connection, _ = self.socket.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            connection.settimeout(0.5)
            self.connections.append(connection)
            worker = threading.Thread(
                target=self.handle, args=(connection,), daemon=True
            )
            self.workers.append(worker)
            worker.start()

    def handle(self, connection: socket.socket):
        try:
            while not self.stop.is_set():
                # Frames are deliberately bounded even in this local fixture.
                try:
                    header = exact(connection, 4)
                except socket.timeout:
                    continue
                size = struct.unpack("<I", header)[0]
                if not 4 <= size <= 64 * 1024:
                    raise AssertionError("invalid server frame length")
                data = exact(connection, size)
                code = struct.unpack_from("<I", data)[0]
                body = data[4:]
                if code == 1:
                    username, position = take_string(body)
                    password, position = take_string(body, position)
                    if username != "fixture-user" or password != "fixture-password":
                        raise AssertionError(
                            "native client did not encode fixture login correctly"
                        )
                    # Check the wire hash without logging fixture credentials.
                    version = struct.unpack_from("<I", body, position)[0]
                    encoded_hash, position = take_string(body, position + 4)
                    expected_hash = hashlib.md5(
                        (username + password).encode()
                    ).hexdigest()
                    if encoded_hash != expected_hash or version == 0:
                        raise AssertionError("native login hash/version mismatch")
                    self.login_seen.set()
                    response = (
                        b"\x00" + string("INVALIDPASS")
                        if self.reject
                        else b"\x01" + string("loopback fixture")
                    )
                    connection.sendall(frame(1, response))
                elif code == 26:
                    query, _ = take_string(body, 4)  # skip search correlation token
                    self.searches.append(query)
                # Post-login status, port and distributed-tree messages are legal
                # but irrelevant to these tests. No fictitious search hits are sent.
        except (EOFError, ConnectionResetError, BrokenPipeError):
            pass
        except OSError as error:
            if not self.stop.is_set():
                self.errors.append(error)
        except Exception as error:
            self.errors.append(error)
        finally:
            connection.close()


def choose_jdk(explicit: Path | None) -> Path:
    if explicit:
        return explicit
    installed = sorted((ROOT / ".deps/tools").glob("jdk-21*"))
    if installed:
        return installed[0]
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"])
    raise SystemExit("JDK missing: pass --java-home or bootstrap the pinned JDK 21")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--library", type=Path)
    args = parser.parse_args()
    jdk = choose_jdk(args.java_home)
    library = (
        args.library or ROOT / "native/soulseek/target/release/libbitchord_soulseek.so"
    )
    library = library.resolve()
    if not library.is_file():
        raise SystemExit(f"Build the host JNI library first; missing {library}")
    json_jar = ROOT / ".deps/tools/json.jar"
    native_class = (
        ROOT
        / "extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekNative.java"
    )
    nostr_class = native_class.with_name("NostrNative.java")
    probe_class = native_class.with_name("RelayProbeNative.java")
    if not json_jar.is_file() or not native_class.is_file():
        raise SystemExit("Missing pinned json.jar or real SoulseekNative.java")
    out = ROOT / "build/soulseek-wire"
    classes = out / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            str(jdk / "bin/javac"),
            "--release",
            "8",
            "-cp",
            str(json_jar),
            "-d",
            str(classes),
            str(native_class),
            str(nostr_class),
            str(probe_class),
            str(Path(__file__).with_name("SoulseekJniWireTest.java")),
            str(Path(__file__).with_name("NostrJniTest.java")),
        ],
        check=True,
    )
    subprocess.run(
        [
            str(jdk / "bin/java"),
            f"-Djava.library.path={library.parent}",
            "-cp",
            str(classes) + os.pathsep + str(json_jar),
            "app.bitchord.soulseek.NostrJniTest",
        ],
        check=True,
        timeout=10,
    )
    for mode in ("reject", "success"):
        with Server(reject=mode == "reject") as server, tempfile.TemporaryDirectory(
            prefix="jni-fixture-", dir=out
        ) as home:
            result = subprocess.run(
                [
                    str(jdk / "bin/java"),
                    f"-Djava.library.path={library.parent}",
                    "-cp",
                    str(classes) + os.pathsep + str(json_jar),
                    "app.bitchord.soulseek.SoulseekJniWireTest",
                    "127.0.0.1",
                    str(server.port),
                    home,
                    mode,
                ],
                capture_output=True,
                text=True,
                timeout=30,
            )
            print(result.stdout, end="")
            if result.stderr:
                print(result.stderr, end="")
            result.check_returncode()
            if not server.login_seen.is_set():
                raise AssertionError("JNI never sent a real login frame")
            if server.errors:
                raise AssertionError(f"loopback server error: {server.errors[0]}")
            if mode == "success" and "fixture recording" not in server.searches:
                raise AssertionError("JNI never sent a real search frame")
            if mode == "success" and "cancel this search" not in server.searches:
                raise AssertionError(
                    "close-during-search case did not enter the real search"
                )
    print(
        "PASS: actual JNI library, login wire framing/rejection, search JSON and lifecycle"
    )


if __name__ == "__main__":
    main()
