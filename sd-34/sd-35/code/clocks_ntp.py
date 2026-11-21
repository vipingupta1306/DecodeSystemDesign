"""
Video 35: Clock Skew & NTP (Decode System Design)
Language: Python 3 (standard library only). Run: python3 clocks_ntp.py

PROBLEM 1: last write wins trusts server timestamps; Mumbai's clock is 300 ms ahead.
PROBLEM 2: measuring a duration with the wall clock (time.time) goes negative when
           NTP steps the clock back.
SOLUTION:  NTP offset math, slew instead of step, time.monotonic() for durations,
           and an uncertainty window (TrueTime-style commit wait) when order matters.
"""
import time

# ---------------------------------------------------------------------------
# A simulated server clock: real time + offset + drift (ppm)
# ---------------------------------------------------------------------------
class ServerClock:
    def __init__(self, offset_ms=0.0, drift_ppm=0.0):
        self.offset_ms = offset_ms
        self.drift_ppm = drift_ppm

    def now_ms(self, real_ms):
        drift = real_ms * self.drift_ppm / 1_000_000
        return real_ms + self.offset_ms + drift


# ---------------------------------------------------------------------------
# PROBLEM 1: last write wins with a skewed clock
# ---------------------------------------------------------------------------
def lww_problem():
    mumbai = ServerClock(offset_ms=+300)     # 300 ms ahead
    london = ServerClock(offset_ms=0)
    kabir = {"op": "add charger", "ts": mumbai.now_ms(300)}       # real time 300
    sara = {"op": "remove headphones", "ts": london.now_ms(500)}  # real time 500 (later!)
    winner = max(kabir, sara, key=lambda w: w["ts"])
    print(f"PROBLEM 1: Kabir ts={kabir['ts']:.0f}, Sara ts={sara['ts']:.0f} "
          f"-> LWW keeps '{winner['op']}' although Sara wrote later")


# ---------------------------------------------------------------------------
# Drift: 20 ppm for one day
# ---------------------------------------------------------------------------
def drift_per_day(ppm=20):
    seconds = 86_400
    return seconds * ppm / 1_000_000          # seconds of error per day


# ---------------------------------------------------------------------------
# SOLUTION A: NTP offset and delay from four timestamps
# ---------------------------------------------------------------------------
def ntp_offset_delay(t1, t2, t3, t4):
    """
    t1: client sends (client clock)    t2: server receives (server clock)
    t3: server replies (server clock)  t4: client receives (client clock)
    offset < 0 means the client's clock is AHEAD of the server.
    """
    delay = (t4 - t1) - (t3 - t2)
    offset = ((t2 - t1) + (t3 - t4)) / 2
    return offset, delay


def best_sample(samples):
    """NTP trusts the sample with the smallest round trip (least room for error)."""
    results = [ntp_offset_delay(*s) for s in samples]
    return min(results, key=lambda r: r[1])


# ---------------------------------------------------------------------------
# SOLUTION B: slew (change speed) instead of step (jump)
# ---------------------------------------------------------------------------
def slew_seconds_needed(offset_ms, max_ppm=500):
    """At 500 ppm the clock gains/loses 0.5 ms per real second."""
    ms_per_second = max_ppm / 1000
    return abs(offset_ms) / ms_per_second


# ---------------------------------------------------------------------------
# PROBLEM 2 and SOLUTION C: wall clock vs monotonic clock
# ---------------------------------------------------------------------------
class FakeWallClock:
    """Stands in for time.time(); NTP may step it."""
    def __init__(self, start):
        self.t = start
    def time(self):
        return self.t
    def advance(self, s):
        self.t += s
    def ntp_step(self, s):
        self.t += s


def duration_problem():
    wall = FakeWallClock(start=36_005.0)      # 10:00:05
    start = wall.time()
    wall.advance(0.2)                         # request runs 0.2 s ...
    wall.ntp_step(-1.0)                       # ... NTP steps the clock back 1 s
    print(f"PROBLEM 2: duration with wall clock = {wall.time() - start:+.1f} s")


def timed_request(work):
    """SOLUTION C: durations, timeouts, leases -> monotonic clock."""
    start = time.monotonic()                  # never jumps, only moves forward
    work()
    return time.monotonic() - start


def lease_valid(acquired_at_monotonic, lease_s=5.0):
    return time.monotonic() - acquired_at_monotonic < lease_s


# ---------------------------------------------------------------------------
# SOLUTION D: when order matters, wait out the clock uncertainty (TrueTime idea)
# ---------------------------------------------------------------------------
def commit_with_wait(now_ms, epsilon_ms):
    """
    Clock says 'now is in [now - eps, now + eps]'. Use the latest bound as the
    commit timestamp and make the write visible only after that bound has surely
    passed everywhere: wait 2 * eps.
    """
    commit_ts = now_ms + epsilon_ms
    wait_ms = 2 * epsilon_ms
    return commit_ts, wait_ms


if __name__ == "__main__":
    lww_problem()
    print(f"Drift at 20 ppm: {drift_per_day():.2f} s per day")
    offset, delay = ntp_offset_delay(t1=1000, t2=725, t3=726, t4=1051)
    print(f"SOLUTION A: delay = {delay} ms, offset = {offset:+.0f} ms (Mumbai is 300 ms ahead)")
    off, d = best_sample([(1000, 725, 726, 1051), (2000, 1745, 1746, 2101), (3000, 2712, 2713, 3027)])
    print(f"           best of 3 samples: delay {d} ms, offset {off:+.1f} ms")
    print(f"SOLUTION B: slewing 300 ms at 500 ppm takes {slew_seconds_needed(300) / 60:.0f} minutes")
    duration_problem()
    print(f"SOLUTION C: monotonic duration = {timed_request(lambda: time.sleep(0.05)):.2f} s "
          f"| lease valid: {lease_valid(time.monotonic())}")
    ts, wait = commit_with_wait(now_ms=10_000_000_500, epsilon_ms=4)
    print(f"SOLUTION D: commit timestamp {ts}, wait {wait} ms before the write is visible")
