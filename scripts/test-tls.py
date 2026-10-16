#!/usr/bin/env python3
"""Exercise the actual Java handler over TLS using a second, stdlib-only client.

Test-only certificate trust: authenticate the exact DER pin before sending the secret.
The fixture listens on loopback and is never shipped in either application.
"""
import hashlib
from pathlib import Path
import shutil
import socket
import ssl
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SECRET = bytes([0x5A]) * 32


def string(value):
    data = value.encode("utf-8")
    return struct.pack(">I", len(data)) + data


def read(connection, count):
    result = bytearray()
    while len(result) < count:
        chunk = connection.recv(count - len(result))
        if not chunk:
            raise EOFError("Short response")
        result.extend(chunk)
    return bytes(result)


def text(connection):
    size, = struct.unpack(">I", read(connection, 4))
    assert size <= 512
    return read(connection, size).decode("utf-8")


def status(connection):
    code = read(connection, 1)
    if code == b"\1":
        raise ValueError(text(connection))
    assert code == b"\0"


def run():
    with tempfile.TemporaryDirectory(prefix="openbridge-tls-") as temporary:
        keystore = str(Path(temporary) / "fixture.p12")
        keytool = [shutil.which("keytool")] if shutil.which("keytool") else ["java", "-m", "java.base/sun.security.tools.keytool.Main"]
        subprocess.run(keytool + ["-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                       "-storetype", "PKCS12", "-keystore", keystore, "-storepass", "test-only", "-keypass", "test-only",
                       "-dname", "CN=OpenBridge-CI", "-validity", "1"], check=True, capture_output=True)
        process = subprocess.Popen(["java", "-cp", str(ROOT / "build/protocol-tests"),
                                    "org.openbridge.protocol.TLSFixture", keystore], stdout=subprocess.PIPE, text=True)
        try:
            import selectors
            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ)
            assert selector.select(timeout=15), "TLS fixture startup timed out"
            ready = process.stdout.readline().strip().split()
            assert len(ready) == 2, "TLS fixture failed to start; inspect its stderr (socket access is required)"
            port, fingerprint = ready
            selector.close()
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE  # Replaced by the exact certificate pin below.

            def connect(operation, document_id="", secret=SECRET, pin=fingerprint):
                connection = context.wrap_socket(socket.create_connection(("127.0.0.1", int(port)), timeout=5), server_hostname="OpenBridge-CI")
                actual = hashlib.sha256(connection.getpeercert(binary_form=True)).hexdigest()
                if actual != pin:
                    connection.close()
                    raise ValueError("Certificate pin mismatch")
                connection.sendall(b"OB01" + secret + bytes([operation]) + string(document_id))
                return connection

            def listing(document_id=""):
                with connect(1, document_id) as connection:
                    status(connection)
                    count, = struct.unpack(">I", read(connection, 4))
                    assert count <= 10000
                    entries = []
                    for _ in range(count):
                        identifier, name = text(connection), text(connection)
                        directory, size = struct.unpack(">Bq", read(connection, 9))
                        entries.append((identifier, name, directory, size))
                    return entries

            assert [entry[1] for entry in listing()] == ["Nested", "hello.txt"]
            assert listing("folder-id") == []
            body = bytes(range(256)) * 1000
            for name, data in [("résumé.bin", body), ("empty", b"")]:
                with connect(3) as connection:
                    connection.sendall(string(name) + struct.pack(">q", len(data)))
                    status(connection)
                    for start in range(0, len(data), 4093):
                        connection.sendall(data[start:start + 4093])
                    status(connection)
                with connect(2, "file:" + name) as connection:
                    status(connection)
                    size, = struct.unpack(">q", read(connection, 8))
                    assert size == len(data) and read(connection, size) == data
            for name in ["hello.txt", "../escape", "a\\b", "a\0b"]:
                with connect(3) as connection:
                    connection.sendall(string(name) + struct.pack(">q", 0))
                    try:
                        status(connection)
                        raise AssertionError("Unsafe name/collision accepted")
                    except ValueError:
                        pass
            with connect(3) as connection:
                connection.sendall(string("partial") + struct.pack(">q", 1000))
                status(connection)
                connection.sendall(b"short body")
            assert not any(entry[1] == "partial" for entry in listing())
            for pin, secret in [("00" * 32, SECRET), (fingerprint, b"\0" * 32)]:
                try:
                    with connect(1, secret=secret, pin=pin) as connection:
                        status(connection)
                    raise AssertionError("Bad credentials accepted")
                except ValueError:
                    pass
            try:
                listing("../../etc")
                raise AssertionError("Traversal accepted")
            except ValueError:
                pass
            with connect(2, "file:hello.txt") as connection:
                status(connection)
                size, = struct.unpack(">q", read(connection, 8))
                assert read(connection, size) == b"Hello from Android\n"
            print("TLS integration passed: pin/auth rejection, browse, binary/Unicode/empty transfers, collision and disconnect cleanup.")
        finally:
            process.terminate()
            process.wait(timeout=10)


if __name__ == "__main__":
    run()
