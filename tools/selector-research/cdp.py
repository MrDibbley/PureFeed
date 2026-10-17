"""Minimal stdlib-only Chrome DevTools client: evaluate a JS file in the WebView page.

usage: python3 -I tools/selector-research/cdp.py <port> <script.js>
Prints the JSON value the script's last expression returns (use returnByValue).
"""
import base64, json, os, socket, struct, sys, urllib.request

port, js_path = int(sys.argv[1]), sys.argv[2]
targets = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
page = next(t for t in targets if t.get("type") == "page" and "facebook" in t.get("url", ""))
ws_url = page["webSocketDebuggerUrl"]
path = ws_url.split(str(port), 1)[1]

sock = socket.create_connection(("127.0.0.1", port), timeout=60)
key = base64.b64encode(os.urandom(16)).decode()
sock.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
              f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
resp = b""
while b"\r\n\r\n" not in resp:
    resp += sock.recv(4096)
assert b" 101 " in resp.split(b"\r\n")[0], resp


def recv_exact(n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def send_text(text):
    data = text.encode()
    mask = os.urandom(4)
    n = len(data)
    header = bytes([0x81])
    if n < 126:
        header += bytes([0x80 | n])
    elif n < 65536:
        header += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", n)
    sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def recv_message():
    parts = []
    while True:
        b1, b2 = recv_exact(2)
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", recv_exact(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", recv_exact(8))[0]
        parts.append(recv_exact(n))
        if b1 & 0x80:
            return b"".join(parts).decode()


expr = open(js_path).read()
send_text(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {
    "expression": expr, "returnByValue": True, "awaitPromise": True}}))
while True:
    msg = json.loads(recv_message())
    if msg.get("id") == 1:
        break
res = msg.get("result", {})
if "exceptionDetails" in res:
    print("EXCEPTION:", json.dumps(res["exceptionDetails"], indent=1)[:3000])
else:
    print(json.dumps(res.get("result", {}).get("value"), indent=1, ensure_ascii=False))
