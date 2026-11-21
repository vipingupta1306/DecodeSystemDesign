/*
 * Video 34: Multi-Region Active-Active (Decode System Design)
 * Language: Java 17 + Spring Boot 3. Run: mvn spring-boot:run
 *
 * Two regions (Mumbai, London) both accept writes and replicate to each other.
 * PROBLEM 1: replication with no origin tag -> a change loops between regions forever.
 * SOLUTION 1: tag each change with its origin region + a change ID; ship only your own
 *             changes; apply each change ID once.
 * PROBLEM 2: the family cart stored as ONE value with last write wins -> a write is lost.
 * SOLUTION 2: store the cart as operations (add/remove) and merge them; counters per region.
 */
package com.decode.activeactive;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@SpringBootApplication
public class ActiveActiveApplication implements CommandLineRunner {
    // ---------------------------------------------------------------------
    // PROBLEM 1: naive replication (no origin tag)
    // ---------------------------------------------------------------------
    static class NaiveRegion {
        String name;
        List<Map<String, Object>> log = new ArrayList<>();
        NaiveRegion peer;

        NaiveRegion(String name) { this.name = name; }

        void write(Map<String, Object> change) {
            log.add(change);          // every applied change lands in the log
            peer.receive(change);     // ...and the replicator ships it
        }

        void receive(Map<String, Object> change) {
            write(change);            // applying a remote change logs it again -> ships back
        }
    }

    static void naiveLoopDemo() {
        NaiveRegion mumbai = new NaiveRegion("Mumbai"), london = new NaiveRegion("London");
        mumbai.peer = london; london.peer = mumbai;
        try {
            mumbai.write(Map.of("key", "price:headphones", "value", 999));
        } catch (StackOverflowError e) {
            System.out.println("PROBLEM 1: change bounced Mumbai <-> London until the stack blew up "
                    + "(" + mumbai.log.size() + " copies in Mumbai's log)");
        }
    }

    // ---------------------------------------------------------------------
    // SOLUTION 1: origin tag + change ID
    // ---------------------------------------------------------------------
    record Change(String id, String origin, String key, Object value) {}

    static class Region {
        String name;
        Map<String, Object> data = new HashMap<>();
        List<Change> log = new ArrayList<>();      // change log (what the replicator reads)
        Set<String> applied = new HashSet<>();     // change IDs already applied (dedupe)
        List<Region> peers = new ArrayList<>();

        Region(String name) { this.name = name; }

        Change localWrite(String key, Object value) {
            Change change = new Change(UUID.randomUUID().toString(), name, key, value);
            apply(change);
            return change;
        }

        void apply(Change change) {
            if (applied.contains(change.id())) return;   // same batch delivered twice -> skip
            applied.add(change.id());
            data.put(change.key(), change.value());
            log.add(change);
        }

        /** Background replicator: send ONLY changes that started here. */
        void ship() {
            List<Change> own = new ArrayList<>();
            for (Change c : log) if (c.origin().equals(name)) own.add(c);
            for (Region peer : peers) {
                for (Change c : own) peer.apply(c);
                for (Change c : own) peer.apply(c);  // network resends the batch: harmless
            }
        }
    }

    static void originTagDemo() {
        Region mumbai = new Region("Mumbai"), london = new Region("London");
        mumbai.peers.add(london); london.peers.add(mumbai);
        mumbai.localWrite("price:headphones", 999);
        london.localWrite("cart:sara", List.of("shoes"));
        for (int i = 0; i < 3; i++) { mumbai.ship(); london.ship(); }   // replicate a few rounds
        System.out.println("SOLUTION 1: Mumbai log size " + mumbai.log.size()
                + " | London log size " + london.log.size()
                + " | same data: " + mumbai.data.equals(london.data));
    }

    // ---------------------------------------------------------------------
    // PROBLEM 2: family cart as one value, last write wins
    // ---------------------------------------------------------------------
    record Versioned(Set<String> value, long timestamp) {}

    static void lwwCartDemo() {
        Set<String> start = new TreeSet<>(Set.of("headphones", "phone case"));
        // Same second: Sara (London) removes headphones, Kabir (Mumbai) adds a charger.
        Set<String> saraSet = new TreeSet<>(start); saraSet.remove("headphones");
        Set<String> kabirSet = new TreeSet<>(start); kabirSet.add("charger");
        Versioned saraCart = new Versioned(saraSet, 10_000_000_500L);    // timestamp in ms
        Versioned kabirCart = new Versioned(kabirSet, 10_000_000_600L);  // Mumbai clock is ahead
        Versioned winner = saraCart.timestamp() > kabirCart.timestamp() ? saraCart : kabirCart;
        System.out.println("PROBLEM 2: last write wins keeps " + new TreeSet<>(winner.value())
                + " -> Sara's removal is lost");
    }

    // ---------------------------------------------------------------------
    // SOLUTION 2: cart as operations (add-wins set) + counter per region
    // ---------------------------------------------------------------------
    /** Each add gets a unique tag; a remove deletes only the tags it has seen. */
    static class OpCart {
        Map<String, String> adds = new HashMap<>();   // tag -> item
        Set<String> removed = new HashSet<>();

        Object[] add(String item) {
            String tag = UUID.randomUUID().toString();
            adds.put(tag, item);
            return new Object[]{"add", Set.of(tag), item, tag};
        }

        Object[] remove(String item) {
            Set<String> tags = new HashSet<>();
            for (var e : adds.entrySet())
                if (e.getValue().equals(item) && !removed.contains(e.getKey())) tags.add(e.getKey());
            removed.addAll(tags);
            return new Object[]{"remove", tags, item, null};
        }

        @SuppressWarnings("unchecked")
        void apply(Object[] op) {
            if (op[0].equals("add")) adds.put((String) op[3], (String) op[2]);
            else removed.addAll((Set<String>) op[1]);
        }

        List<String> items() {
            TreeSet<String> out = new TreeSet<>();
            for (var e : adds.entrySet()) if (!removed.contains(e.getKey())) out.add(e.getValue());
            return new ArrayList<>(out);
        }
    }

    static void mergeCartDemo() {
        OpCart london = new OpCart(), mumbai = new OpCart();
        for (String item : List.of("headphones", "phone case")) {   // both start with the same cart
            Object[] op = london.add(item); mumbai.apply(op);
        }
        Object[] opSara = london.remove("headphones");   // London
        Object[] opKabir = mumbai.add("charger");        // Mumbai, same second
        london.apply(opKabir); mumbai.apply(opSara);     // replication, any order
        System.out.println("SOLUTION 2: London " + london.items() + " | Mumbai " + mumbai.items());
    }

    /** Likes: each region counts its own; total = sum. Nothing is overwritten. */
    static class RegionCounter {
        Map<String, Integer> perRegion = new HashMap<>();

        void like(String region, int n) { perRegion.merge(region, n, Integer::sum); }

        void merge(RegionCounter other) {
            other.perRegion.forEach((r, v) -> perRegion.merge(r, v, Math::max));
        }

        int total() { return perRegion.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    static void counterDemo() {
        RegionCounter london = new RegionCounter(), mumbai = new RegionCounter();
        london.like("London", 40); mumbai.like("Mumbai", 60);
        london.merge(mumbai); mumbai.merge(london);
        System.out.println("SOLUTION 2b: likes London = " + london.total() + " | Mumbai = " + mumbai.total());
    }

    // ---------------------------------------------------------------------
    // Home region routing: a travelling user's writes go to her home region
    // ---------------------------------------------------------------------
    @Service
    static class HomeRouter {
        private final Map<String, String> home = Map.of("sara", "London", "kabir", "Mumbai");

        String routeWrite(String user, String arrivedAt) {
            String homeRegion = home.get(user);
            if (homeRegion.equals(arrivedAt))
                return user + ": commit locally in " + homeRegion + " (~5 ms)";
            return user + ": arrived at " + arrivedAt + ", forward to home " + homeRegion + " (+120 ms)";
        }
    }

    /** Every region runs this controller; the load balancer / GeoDNS decides which one a user reaches. */
    @RestController
    static class WriteController {
        private final HomeRouter router;

        WriteController(HomeRouter router) { this.router = router; }

        @PostMapping("/write")
        String write(@RequestParam String user, @RequestParam String region) {
            return router.routeWrite(user, region);
        }
    }

    private final HomeRouter router;

    ActiveActiveApplication(HomeRouter router) { this.router = router; }

    @Override
    public void run(String... args) {
        naiveLoopDemo();
        originTagDemo();
        lwwCartDemo();
        mergeCartDemo();
        counterDemo();
        System.out.println(router.routeWrite("sara", "London"));
        System.out.println(router.routeWrite("sara", "Mumbai"));
    }

    public static void main(String[] args) {
        SpringApplication.run(ActiveActiveApplication.class, args);
    }
}
