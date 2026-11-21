"""
Video 36: Lamport & Vector Clocks (Decode System Design)
Language: Python 3 (standard library only). Run: python3 logical_clocks.py

PROBLEM:  logs sorted by wall-clock time show the refund 2 ms BEFORE the cancel that caused it.
SOLUTION 1: Lamport clock -> cause always gets a smaller number.
SOLUTION 2: vector clock -> also tells "before", "after" or "concurrent".
SOLUTION 3: version vectors on a replicated cart -> detect concurrent writes and merge.
"""

# ---------------------------------------------------------------------------
# PROBLEM: sort by wall clock (payment server's clock is 2 ms behind)
# ---------------------------------------------------------------------------
def wall_clock_problem():
    logs = [("10:00:00.010", "order", "cancel"),
            ("10:00:00.008", "payment", "refund")]        # happened AFTER the cancel
    for ts, server, event in sorted(logs):
        print(f"PROBLEM  {ts} {server:8} {event}")


# ---------------------------------------------------------------------------
# SOLUTION 1: Lamport clock
# ---------------------------------------------------------------------------
class LamportClock:
    def __init__(self, name, start=0):
        self.name, self.counter = name, start

    def event(self):                       # rule 1: +1 before every event
        self.counter += 1
        return self.counter

    def send(self):                        # rule 2: the message carries the counter
        return self.event()

    def receive(self, msg_counter):        # rule 3: max(mine, received) + 1
        self.counter = max(self.counter, msg_counter) + 1
        return self.counter


def lamport_demo():
    order, payment = LamportClock("order", start=4), LamportClock("payment", start=2)
    cancel = order.send()                  # cancel + "refund please" message -> 5
    payment.receive(cancel)                # max(2, 5) + 1 -> 6
    refund = payment.event()               # -> 7
    log = sorted([(refund, "payment", "refund"), (cancel, "order", "cancel")])
    # ties are broken by server name, so every server sorts the same way
    for counter, server, event in log:
        print(f"LAMPORT  {counter} {server:8} {event}")


# ---------------------------------------------------------------------------
# SOLUTION 2: vector clock
# ---------------------------------------------------------------------------
SERVERS = ("order", "payment", "warehouse")

class VectorClock:
    def __init__(self, me):
        self.me = me
        self.v = {s: 0 for s in SERVERS}

    def event(self):                       # own event: +1 on my own entry
        self.v[self.me] += 1
        return dict(self.v)

    def send(self):                        # send the whole vector
        return self.event()

    def receive(self, other):              # entry-wise max, then +1 my own
        for s in SERVERS:
            self.v[s] = max(self.v[s], other[s])
        return self.event()


def compare(a, b):
    """'before', 'after', 'equal' or 'concurrent'."""
    a_le_b = all(a[s] <= b[s] for s in SERVERS)
    b_le_a = all(b[s] <= a[s] for s in SERVERS)
    if a_le_b and b_le_a:
        return "equal"
    if a_le_b:
        return "before"
    if b_le_a:
        return "after"
    return "concurrent"


def fmt(v):
    return "[" + ", ".join(str(v[s]) for s in SERVERS) + "]"


def vector_demo():
    order, payment, warehouse = (VectorClock(s) for s in SERVERS)
    placed = order.send()                  # [1, 0, 0] "pack it" -> warehouse
    warehouse.receive(placed)              # [1, 0, 1]
    slip = warehouse.event()               # [1, 0, 2]
    cancel = order.send()                  # [2, 0, 0] "refund" -> payment
    payment.receive(cancel)                # [2, 1, 0]
    refund = payment.event()               # [2, 2, 0]
    print(f"VECTOR   cancel {fmt(cancel)} vs refund {fmt(refund)}: {compare(cancel, refund)}")
    print(f"VECTOR   refund {fmt(refund)} vs slip {fmt(slip)}: {compare(refund, slip)}")
    if compare(slip, cancel) == "concurrent":
        print("VECTOR   slip is concurrent with cancel -> check order state before shipping")


# ---------------------------------------------------------------------------
# SOLUTION 3: version vectors on the family cart (per replica, not per client)
# ---------------------------------------------------------------------------
REPLICAS = ("mumbai", "london")

def vv_compare(a, b):
    a_le_b = all(a[r] <= b[r] for r in REPLICAS)
    b_le_a = all(b[r] <= a[r] for r in REPLICAS)
    if a_le_b and b_le_a:
        return "equal"
    return "before" if a_le_b else "after" if b_le_a else "concurrent"


def write(version, replica, items):
    vv = dict(version["vv"]); vv[replica] += 1
    return {"vv": vv, "items": set(items)}


def reconcile(a, b, replica, merge):
    rel = vv_compare(a["vv"], b["vv"])
    if rel in ("before", "equal"):
        return b                              # newer simply replaces
    if rel == "after":
        return a
    # concurrent: keep both as siblings, merge, then step past both
    merged_vv = {r: max(a["vv"][r], b["vv"][r]) for r in REPLICAS}
    merged_vv[replica] += 1
    return {"vv": merged_vv, "items": merge(a["items"], b["items"])}


def cart_demo():
    base = {"vv": {"mumbai": 3, "london": 2}, "items": {"headphones", "phone case"}}
    kabir = write(base, "mumbai", base["items"] | {"charger"})        # [4, 2]
    sara = write(base, "london", base["items"] - {"headphones"})      # [3, 3]
    removed = base["items"] - sara["items"]
    merge = lambda x, y: (x | y) - removed    # app-level merge: keep adds, honour removes
    result = reconcile(kabir, sara, "mumbai", merge)
    print(f"CART     {kabir['vv']} vs {sara['vv']}: {vv_compare(kabir['vv'], sara['vv'])}"
          f" -> merged {sorted(result['items'])} at {result['vv']}")


if __name__ == "__main__":
    wall_clock_problem()
    lamport_demo()
    vector_demo()
    cart_demo()
