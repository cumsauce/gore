#!/usr/bin/env python3
"""Minimal RCON client for driving the dev server. Usage: rcon.py <command> [command...]"""

import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 25585
PASSWORD = "gore"


def send_packet(sock, pid, ptype, body):
    payload = struct.pack("<ii", pid, ptype) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(payload)) + payload)


def recv_packet(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            return None
        raw += chunk
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    pid, ptype = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return pid, ptype, body


def connect(deadline):
    while time.time() < deadline:
        try:
            sock = socket.create_connection((HOST, PORT), timeout=5)
        except OSError:
            time.sleep(2)
            continue
        try:
            send_packet(sock, 1, 3, PASSWORD)
            pkt = recv_packet(sock)
            if pkt and pkt[0] == 1:
                sock.settimeout(1.5)
                return sock
        except OSError:
            pass
        sock.close()
        time.sleep(2)
    raise SystemExit("rcon: auth never succeeded")


def run(sock, cmd):
    send_packet(sock, 2, 2, cmd)
    chunks = []
    try:
        while True:
            pkt = recv_packet(sock)
            if pkt is None:
                break
            chunks.append(pkt[2])
    except socket.timeout:
        pass
    return "\n".join(c for c in chunks if c)


def main():
    deadline = time.time() + 180
    sock = connect(deadline)
    # wait until the server actually processes commands
    while time.time() < deadline:
        out = run(sock, "list")
        if out.strip():
            print("> list")
            print(out)
            break
        time.sleep(3)
    for cmd in sys.argv[1:]:
        if not cmd.strip():
            continue
        print("> " + cmd)
        out = run(sock, cmd)
        if out:
            print(out)


if __name__ == "__main__":
    main()
