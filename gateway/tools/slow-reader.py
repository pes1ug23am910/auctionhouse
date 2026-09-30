"""A real blocked TCP receiver; credentials arrive only over the owned stdin pipe."""
import json
import socket
import sys

config = json.loads(sys.stdin.readline())
if config['host'] != '127.0.0.1' or not 1024 <= config['port'] <= 65535:
    raise SystemExit('Only the owned loopback listener is accepted')
with socket.socket() as client:
    client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
    client.settimeout(10)
    client.connect((config['host'], config['port']))
    request = ('GET ' + config['path'] + ' HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: ' + config['cookie']
               + '\r\nConnection: close\r\nAccept: text/event-stream\r\n\r\n')
    client.sendall(request.encode())
    header = bytearray()
    while not header.endswith(b'\r\n\r\n'):
        value = client.recv(1)
        if not value:
            raise SystemExit('Premature EOF before HTTP headers')
        header.extend(value)
        if len(header) > 16384:
            raise SystemExit('Header limit')
    status = int(header.split(b' ', 2)[1])
    print(json.dumps({'ready': True, 'status': status, 'receiveBufferBytes': client.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)}), flush=True)
    # Do not consume any body bytes until the owning collector requests recovery.
    if sys.stdin.readline().strip() == 'drain':
        total = 0
        try:
            while True:
                value = client.recv(65536)
                if not value:
                    print(json.dumps({'eof': True, 'receivedBytesAfterResume': total}), flush=True)
                    break
                total += len(value)
        except (ConnectionError, TimeoutError) as error:
            print(json.dumps({'eof': False, 'receivedBytesAfterResume': total, 'failure': type(error).__name__}), flush=True)
