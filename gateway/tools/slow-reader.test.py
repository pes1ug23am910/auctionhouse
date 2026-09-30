"""Bounded transport regression for the optional real-stack fault receiver."""
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
import unittest


class SlowReaderTest(unittest.TestCase):
    def test_premature_header_eof_fails_instead_of_spinning(self):
        with socket.socket() as server:
            server.bind(('127.0.0.1', 0))
            server.listen(1)
            server.settimeout(3)

            def serve():
                with server.accept()[0] as client:
                    client.recv(4096)
                    client.sendall(b'HTTP/1.1 200 OK\r\n')

            thread = threading.Thread(target=serve, daemon=True)
            thread.start()
            result = subprocess.run(
                [sys.executable, str(Path(__file__).with_name('slow-reader.py'))],
                input=json.dumps({'host': '127.0.0.1', 'port': server.getsockname()[1],
                                  'path': '/fixture', 'cookie': 'fixture=only'}) + '\n',
                text=True, capture_output=True, timeout=3,
            )
            thread.join(timeout=3)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Premature EOF before HTTP headers', result.stderr)
            self.assertNotIn('fixture=only', result.stderr)
            self.assertEqual(result.stdout, '')


if __name__ == '__main__':
    unittest.main()
