"""Soak test for PureFeed debug on a USB-connected phone.

Simulates a heavy (read-only) user with real touch input: irregular flick scrolling with
reading pauses, pull-to-refresh, Back to the ranked feed, background/resume. Never taps
posts. Every 30 s it samples memory, CPU, DOM/JS metrics and the filter's own cost
(__fbm.stats()). Results: <out_dir>/samples.csv, events.log, gfxinfo.txt, logcat_raw.txt.
logcat_raw.txt can contain other apps' log lines; don't commit it.

usage: python3 -I tools/soak/soak.py <out_dir> [minutes] [package]
Needs adb (Android SDK platform-tools) and a debug build (WebView debugging on).
"""
import base64, csv, json, os, random, socket, struct, subprocess, sys, threading, time, urllib.request

OUT = sys.argv[1]
MINUTES = float(sys.argv[2]) if len(sys.argv) > 2 else 45
PKG = sys.argv[3] if len(sys.argv) > 3 else "app.purefeed.debug"
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PORT = 9224
os.makedirs(OUT, exist_ok=True)
stop = threading.Event()
events_f = open(os.path.join(OUT, "events.log"), "a", buffering=1)


def log(msg):
    line = f"{time.strftime('%H:%M:%S')} {msg}"
    print(line, flush=True)
    events_f.write(line + "\n")


def adb(*args, timeout=30):
    try:
        return subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def sh(cmd, timeout=30):
    return adb("shell", cmd, timeout=timeout)


# ---------------------------------------------------------------- processes

CLK_TCK = 100


def app_pid():
    out = sh(f"pidof {PKG}").strip()
    return int(out.split()[0]) if out else None


def proc_stat(pid):
    """(utime+stime ticks, starttime ticks) or None."""
    s = sh(f"cat /proc/{pid}/stat 2>/dev/null")
    if not s or ")" not in s:
        return None
    f = s.rsplit(")", 1)[1].split()
    return int(f[11]) + int(f[12]), int(f[19])


def renderer_pid(apid):
    """The WebView sandbox process started soonest after the app process."""
    a = proc_stat(apid)
    if not a:
        return None
    best = None
    for line in sh("ps -A -o PID,NAME").splitlines():
        if "com.google.android.webview:sandboxed_process" not in line:
            continue
        pid = int(line.split()[0])
        st = proc_stat(pid)
        if st and st[1] >= a[1] and (best is None or st[1] < best[1]):
            best = (pid, st[1])
    return best[0] if best else None


def pss_kb(pid):
    for line in sh(f"dumpsys meminfo {pid}", timeout=60).splitlines():
        if "TOTAL PSS:" in line:
            return int(line.split("TOTAL PSS:")[1].split()[0])
        if line.strip().startswith("TOTAL") and len(line.split()) > 1 and line.split()[1].isdigit():
            return int(line.split()[1])
    return None


# ---------------------------------------------------------------- devtools

class Cdp:
    def __init__(self, port):
        targets = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=10))
        page = next(t for t in targets if t.get("type") == "page" and "facebook" in t.get("url", ""))
        path = page["webSocketDebuggerUrl"].split(str(port), 1)[1]
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=20)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                           f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            resp += self.sock.recv(4096)
        self.next_id = 0

    def _recv_exact(self, n):
        buf = b""
        while len(buf) < n:
            c = self.sock.recv(n - len(buf))
            if not c:
                raise EOFError
            buf += c
        return buf

    def _recv(self):
        parts = []
        while True:
            b1, b2 = self._recv_exact(2)
            n = b2 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._recv_exact(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._recv_exact(8))[0]
            parts.append(self._recv_exact(n))
            if b1 & 0x80:
                return json.loads(b"".join(parts).decode())

    def call(self, method, params=None):
        self.next_id += 1
        data = json.dumps({"id": self.next_id, "method": method, "params": params or {}}).encode()
        mask = os.urandom(4)
        n = len(data)
        hdr = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536
                               else bytes([0x80 | 127]) + struct.pack(">Q", n))
        self.sock.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))
        while True:
            msg = self._recv()
            if msg.get("id") == self.next_id:
                return msg.get("result", {})

    def eval(self, expr):
        r = self.call("Runtime.evaluate", {"expression": expr, "returnByValue": True, "awaitPromise": True})
        return r.get("result", {}).get("value")

    def close(self):
        self.sock.close()


def cdp():
    pid = app_pid()
    if not pid:
        return None
    adb("forward", f"tcp:{PORT}", f"localabstract:webview_devtools_remote_{pid}")
    try:
        return Cdp(PORT)
    except Exception:
        return None


PAGE_EXPR = """(() => {
  const nav = document.querySelector('[data-testid="nav_bar_component"]');
  return { path: location.pathname, feeds: !!(nav && /feeds/i.test(nav.textContent)),
    units: document.querySelectorAll('[data-type="vscroller"] > [data-tracking-duration-id]').length,
    scrollY: Math.round(scrollY), docH: document.documentElement.scrollHeight,
    stats: window.__fbm ? window.__fbm.stats() : null };
})()"""


# ---------------------------------------------------------------- sampling

FIELDS = ["t", "elapsed_s", "app_pid", "renderer_pid", "app_pss_kb", "renderer_pss_kb", "app_cpu_pct", "renderer_cpu_pct",
          "js_heap_mb", "nodes", "listeners", "documents", "units", "scrollY", "docH", "path", "feeds",
          "filter_batches", "filter_units_tested", "filter_total_ms", "filter_max_batch_ms", "marked", "phase"]
phase = {"name": "start"}


def sampler(t0):
    f = open(os.path.join(OUT, "samples.csv"), "w", newline="")
    w = csv.DictWriter(f, FIELDS)
    w.writeheader()
    prev = {}
    last_app = None
    while not stop.is_set():
        t = time.time()
        row = {"t": time.strftime("%H:%M:%S"), "elapsed_s": int(t - t0), "phase": phase["name"]}
        apid = app_pid()
        if apid != last_app:
            if last_app is not None:
                log(f"!! app process changed {last_app} -> {apid}")
            last_app = apid
        rpid = renderer_pid(apid) if apid else None
        row.update(app_pid=apid, renderer_pid=rpid)
        for key, pid in (("app", apid), ("renderer", rpid)):
            if not pid:
                continue
            row[f"{key}_pss_kb"] = pss_kb(pid)
            st = proc_stat(pid)
            if st and pid in prev:
                ticks, when = prev[pid]
                row[f"{key}_cpu_pct"] = round(100.0 * (st[0] - ticks) / CLK_TCK / (t - when), 1)
            if st:
                prev[pid] = (st[0], t)
        c = cdp()
        if c:
            try:
                c.call("Performance.enable")
                m = {x["name"]: x["value"] for x in c.call("Performance.getMetrics").get("metrics", [])}
                row.update(js_heap_mb=round(m.get("JSHeapUsedSize", 0) / 1e6, 1), nodes=int(m.get("Nodes", 0)),
                           listeners=int(m.get("JSEventListeners", 0)), documents=int(m.get("Documents", 0)))
                p = c.eval(PAGE_EXPR) or {}
                s = p.get("stats") or {}
                row.update(units=p.get("units"), scrollY=p.get("scrollY"), docH=p.get("docH"), path=p.get("path"),
                           feeds=p.get("feeds"), filter_batches=s.get("batches"), filter_units_tested=s.get("unitsTested"),
                           filter_total_ms=s.get("totalMs"), filter_max_batch_ms=s.get("maxBatchMs"), marked=s.get("marked"))
            except Exception as e:
                log(f"sample cdp error: {e}")
            finally:
                c.close()
        w.writerow(row)
        f.flush()
        stop.wait(max(1, 30 - (time.time() - t)))
    f.close()


# ---------------------------------------------------------------- user simulation

def swipe(x1, y1, x2, y2, ms):
    sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")


def flick_down_the_feed():
    x = random.randint(380, 700)
    y1 = random.randint(1650, 1900)
    swipe(x, y1, x + random.randint(-40, 40), y1 - random.randint(700, 1100), random.randint(140, 320))


def scroll_session(seconds):
    end = time.time() + seconds
    while time.time() < end and not stop.is_set():
        r = random.random()
        if r < 0.10:
            time.sleep(random.uniform(5, 14))                 # reading a post
        elif r < 0.17:
            x = random.randint(380, 700)                      # scroll back up a bit
            swipe(x, 900, x, 1600, random.randint(200, 350))
        else:
            flick_down_the_feed()
        time.sleep(random.uniform(1.0, 3.2))


def to_top_and_pull_refresh():
    c = cdp()
    if c:
        try:
            c.eval("window.scrollTo(0, 0)")
        finally:
            c.close()
    time.sleep(1.5)
    swipe(540, 700, 540, 1600, 700)                           # pull to refresh
    log("pull to refresh")
    time.sleep(12)


def back_to_ranked_feed():
    sh("input keyevent KEYCODE_BACK")
    log("Back (to ranked feed)")
    time.sleep(10)


def background_and_resume():
    sh("input keyevent KEYCODE_HOME")
    log("Home (app backgrounded)")
    time.sleep(random.uniform(20, 40))
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1")
    log("resumed")
    time.sleep(6)


def main():
    t0 = time.time()
    end = t0 + MINUTES * 60
    adb("logcat", "-c", "-b", "all")
    sh(f"dumpsys gfxinfo {PKG} reset")
    logcat = subprocess.Popen([ADB, "logcat", "-v", "time", "-b", "main,system,crash", "*:W"],
                              stdout=open(os.path.join(OUT, "logcat_raw.txt"), "w"), stderr=subprocess.DEVNULL)
    th = threading.Thread(target=sampler, args=(t0,), daemon=True)
    th.start()
    log(f"soak start, {MINUTES} min, {PKG}")
    n = 0
    try:
        while time.time() < end:
            n += 1
            phase["name"] = "scroll"
            scroll_session(random.uniform(150, 240))
            if time.time() >= end:
                break
            if n % 4 == 0:
                phase["name"] = "background"
                background_and_resume()
            elif n % 3 == 0:
                phase["name"] = "ranked"
                back_to_ranked_feed()
                scroll_session(random.uniform(40, 70))
                to_top_and_pull_refresh()
            else:
                phase["name"] = "refresh"
                to_top_and_pull_refresh()
    finally:
        stop.set()
        th.join(timeout=60)
        open(os.path.join(OUT, "gfxinfo.txt"), "w").write(sh(f"dumpsys gfxinfo {PKG}", timeout=60))
        logcat.terminate()
        log("soak end")


if __name__ == "__main__":
    main()
