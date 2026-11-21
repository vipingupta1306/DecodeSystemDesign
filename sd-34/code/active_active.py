"""
Video 34: Multi-Region Active-Active (Decode System Design)
Language: Python 3 (standard library only). Run: python3 active_active.py

Two regions (Mumbai, London) both accept writes and replicate to each other.
PROBLEM 1: replication with no origin tag -> a change loops between regions forever.
SOLUTION 1: tag each change with its origin region + a change ID; ship only your own
            changes; apply each change ID once.
PROBLEM 2: the family cart stored as ONE value with last write wins -> a write is lost.
SOLUTION 2: store the cart as operations (add/remove) and merge them; counters per region.
"""
import uuid

# ---------------------------------------------------------------------------
# PROBLEM 1: naive replication (no origin tag)
# ---------------------------------------------------------------------------
class NaiveRegion:
    def __init__(self, name):
        self.name, self.log, self.peer = name, [], None

    def write(self, change):
        self.log.append(change)          # every applied change lands in the log
        self.peer.receive(change)        # ...and the replicator ships the whole log

    def receive(self, change):
        self.write(change)               # applying a remote change logs it again -> ships back


def naive_loop_demo():
    mumbai, london = NaiveRegion("Mumbai"), NaiveRegion("London")
    mumbai.peer, london.peer = london, mumbai
    try:
        mumbai.write({"key": "price:headphones", "value": 999})
    except RecursionError:
        print("PROBLEM 1: change bounced Mumbai <-> London until the stack blew up "
              f"({len(mumbai.log)} copies in Mumbai's log)")


# ---------------------------------------------------------------------------
# SOLUTION 1: origin tag + change ID
# ---------------------------------------------------------------------------
class Region:
    def __init__(self, name):
        self.name = name
        self.data = {}
        self.log = []                    # change log (what the replicator reads)
        self.applied = set()             # change IDs already applied (dedupe)
        self.peers = []

    def local_write(self, key, value):
        change = {"id": str(uuid.uuid4()), "origin": self.name, "key": key, "value": value}
        self._apply(change)
        return change

    def _apply(self, change):
        if change["id"] in self.applied:          # same batch delivered twice -> skip
            return
        self.applied.add(change["id"])
        self.data[change["key"]] = change["value"]
        self.log.append(change)

    def ship(self):
        """Background replicator: send ONLY changes that started here."""
        own = [c for c in self.log if c["origin"] == self.name]
        for peer in self.peers:
            for c in own:
                peer._apply(c)
            for c in own:                          # network resends the batch: harmless
                peer._apply(c)


def origin_tag_demo():
    mumbai, london = Region("Mumbai"), Region("London")
    mumbai.peers, london.peers = [london], [mumbai]
    mumbai.local_write("price:headphones", 999)
    london.local_write("cart:sara", ["shoes"])
    for _ in range(3):                             # replicate a few rounds
        mumbai.ship(); london.ship()
    print("SOLUTION 1: Mumbai log size", len(mumbai.log), "| London log size", len(london.log),
          "| same data:", mumbai.data == london.data)


# ---------------------------------------------------------------------------
# PROBLEM 2: family cart as one value, last write wins
# ---------------------------------------------------------------------------
def lww_cart_demo():
    start = {"headphones", "phone case"}
    # Same second: Sara (London) removes headphones, Kabir (Mumbai) adds a charger.
    sara_cart = (start - {"headphones"}, 10_000_000_500)        # (value, timestamp in ms)
    kabir_cart = (start | {"charger"}, 10_000_000_600)          # Mumbai clock is ahead
    winner = max(sara_cart, kabir_cart, key=lambda v: v[1])
    print("PROBLEM 2: last write wins keeps", sorted(winner[0]),
          "-> Sara's removal is lost")


# ---------------------------------------------------------------------------
# SOLUTION 2: cart as operations (add-wins set) + counter per region
# ---------------------------------------------------------------------------
class OpCart:
    """Each add gets a unique tag; a remove deletes only the tags it has seen."""
    def __init__(self):
        self.adds = {}      # tag -> item
        self.removed = set()

    def add(self, item):
        tag = str(uuid.uuid4())
        self.adds[tag] = item
        return ("add", tag, item)

    def remove(self, item):
        tags = {t for t, i in self.adds.items() if i == item and t not in self.removed}
        self.removed |= tags
        return ("remove", tags, item)

    def apply(self, op):
        kind, tag, item = op
        if kind == "add":
            self.adds[tag] = item
        else:
            self.removed |= tag

    def items(self):
        return sorted({i for t, i in self.adds.items() if t not in self.removed})


def merge_cart_demo():
    london, mumbai = OpCart(), OpCart()
    for item in ("headphones", "phone case"):     # both regions start with the same cart
        op = london.add(item); mumbai.apply(op)
    op_sara = london.remove("headphones")         # London
    op_kabir = mumbai.add("charger")              # Mumbai, same second
    london.apply(op_kabir); mumbai.apply(op_sara) # replication, any order
    print("SOLUTION 2: London", london.items(), "| Mumbai", mumbai.items())


class RegionCounter:
    """Likes: each region counts its own; total = sum. Nothing is overwritten."""
    def __init__(self):
        self.per_region = {}

    def like(self, region, n=1):
        self.per_region[region] = self.per_region.get(region, 0) + n

    def merge(self, other):
        for r, v in other.per_region.items():
            self.per_region[r] = max(self.per_region.get(r, 0), v)

    def total(self):
        return sum(self.per_region.values())


def counter_demo():
    london, mumbai = RegionCounter(), RegionCounter()
    london.like("London", 40); mumbai.like("Mumbai", 60)
    london.merge(mumbai); mumbai.merge(london)
    print("SOLUTION 2b: likes London =", london.total(), "| Mumbai =", mumbai.total())


# ---------------------------------------------------------------------------
# Home region routing: a travelling user's writes go to her home region
# ---------------------------------------------------------------------------
HOME = {"sara": "London", "kabir": "Mumbai"}

def route_write(user, arrived_at):
    home = HOME[user]
    if home == arrived_at:
        return f"{user}: commit locally in {home} (~5 ms)"
    return f"{user}: arrived at {arrived_at}, forward to home {home} (+120 ms)"


if __name__ == "__main__":
    naive_loop_demo()
    origin_tag_demo()
    lww_cart_demo()
    merge_cart_demo()
    counter_demo()
    print(route_write("sara", "London"))
    print(route_write("sara", "Mumbai"))
