/*
 * Video 36: Lamport & Vector Clocks (Decode System Design)
 * Language: Java 17 + Spring Boot 3. Run: mvn spring-boot:run
 *
 * PROBLEM:    logs sorted by wall-clock time show the refund 2 ms BEFORE the cancel that caused it.
 * SOLUTION 1: Lamport clock -> a cause always gets a smaller number.
 * SOLUTION 2: vector clock  -> also tells "before", "after" or "concurrent".
 * SOLUTION 3: version vectors on a replicated cart -> detect concurrent writes and merge.
 */
package com.decode.logical;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BinaryOperator;

@SpringBootApplication
@RestController
public class LogicalClocksApplication implements CommandLineRunner {

    // ---------------------------------------------------------------------
    // PROBLEM: sort by wall clock (payment server's clock is 2 ms behind)
    // ---------------------------------------------------------------------
    record LogLine(String ts, String server, String event) {}

    static void wallClockProblem() {
        List<LogLine> logs = new ArrayList<>(List.of(
                new LogLine("10:00:00.010", "order", "cancel"),
                new LogLine("10:00:00.008", "payment", "refund")));   // happened AFTER the cancel
        logs.sort(Comparator.comparing(LogLine::ts));
        for (LogLine l : logs) {
            System.out.printf("PROBLEM  %s %-8s %s%n", l.ts(), l.server(), l.event());
        }
    }

    // ---------------------------------------------------------------------
    // SOLUTION 1: Lamport clock
    // ---------------------------------------------------------------------
    static class LamportClock {
        final String name;
        long counter;

        LamportClock(String name, long start) {
            this.name = name;
            this.counter = start;
        }

        long event() {                        // rule 1: +1 before every event
            return ++counter;
        }

        long send() {                         // rule 2: the message carries the counter
            return event();
        }

        long receive(long msgCounter) {       // rule 3: max(mine, received) + 1
            counter = Math.max(counter, msgCounter) + 1;
            return counter;
        }
    }

    record Stamped(long counter, String server, String event) {}

    static void lamportDemo() {
        LamportClock order = new LamportClock("order", 4);
        LamportClock payment = new LamportClock("payment", 2);
        long cancel = order.send();           // cancel + "refund please" message -> 5
        payment.receive(cancel);              // max(2, 5) + 1 -> 6
        long refund = payment.event();        // -> 7
        List<Stamped> log = new ArrayList<>(List.of(
                new Stamped(refund, "payment", "refund"),
                new Stamped(cancel, "order", "cancel")));
        // ties are broken by server name, so every server sorts the same way
        log.sort(Comparator.comparingLong(Stamped::counter).thenComparing(Stamped::server));
        for (Stamped s : log) {
            System.out.printf("LAMPORT  %d %-8s %s%n", s.counter(), s.server(), s.event());
        }
    }

    // ---------------------------------------------------------------------
    // SOLUTION 2: vector clock
    // ---------------------------------------------------------------------
    static final List<String> SERVERS = List.of("order", "payment", "warehouse");

    static class VectorClock {
        final String me;
        final Map<String, Integer> v = new LinkedHashMap<>();

        VectorClock(String me) {
            this.me = me;
            for (String s : SERVERS) v.put(s, 0);
        }

        Map<String, Integer> event() {        // own event: +1 on my own entry
            v.merge(me, 1, Integer::sum);
            return new LinkedHashMap<>(v);
        }

        Map<String, Integer> send() {         // send the whole vector
            return event();
        }

        Map<String, Integer> receive(Map<String, Integer> other) {
            for (String s : SERVERS) {        // entry-wise max, then +1 my own
                v.put(s, Math.max(v.get(s), other.get(s)));
            }
            return event();
        }
    }

    /** "before", "after", "equal" or "concurrent". */
    static String compare(Map<String, Integer> a, Map<String, Integer> b) {
        boolean aLeB = SERVERS.stream().allMatch(s -> a.get(s) <= b.get(s));
        boolean bLeA = SERVERS.stream().allMatch(s -> b.get(s) <= a.get(s));
        if (aLeB && bLeA) return "equal";
        if (aLeB) return "before";
        if (bLeA) return "after";
        return "concurrent";
    }

    static String fmt(Map<String, Integer> v) {
        return SERVERS.stream().map(s -> String.valueOf(v.get(s)))
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    static void vectorDemo() {
        VectorClock order = new VectorClock("order");
        VectorClock payment = new VectorClock("payment");
        VectorClock warehouse = new VectorClock("warehouse");
        var placed = order.send();            // [1, 0, 0] "pack it" -> warehouse
        warehouse.receive(placed);            // [1, 0, 1]
        var slip = warehouse.event();         // [1, 0, 2]
        var cancel = order.send();            // [2, 0, 0] "refund" -> payment
        payment.receive(cancel);              // [2, 1, 0]
        var refund = payment.event();         // [2, 2, 0]
        System.out.printf("VECTOR   cancel %s vs refund %s: %s%n",
                fmt(cancel), fmt(refund), compare(cancel, refund));
        System.out.printf("VECTOR   refund %s vs slip %s: %s%n",
                fmt(refund), fmt(slip), compare(refund, slip));
        if (compare(slip, cancel).equals("concurrent")) {
            System.out.println("VECTOR   slip is concurrent with cancel -> check order state before shipping");
        }
    }

    // ---------------------------------------------------------------------
    // SOLUTION 3: version vectors on the family cart (per replica, not per client)
    // ---------------------------------------------------------------------
    static final List<String> REPLICAS = List.of("mumbai", "london");

    record Version(Map<String, Integer> vv, Set<String> items) {}

    static String vvCompare(Map<String, Integer> a, Map<String, Integer> b) {
        boolean aLeB = REPLICAS.stream().allMatch(r -> a.get(r) <= b.get(r));
        boolean bLeA = REPLICAS.stream().allMatch(r -> b.get(r) <= a.get(r));
        if (aLeB && bLeA) return "equal";
        return aLeB ? "before" : bLeA ? "after" : "concurrent";
    }

    static Map<String, Integer> vv(int mumbai, int london) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("mumbai", mumbai);
        m.put("london", london);
        return m;
    }

    static Version write(Version version, String replica, Set<String> items) {
        Map<String, Integer> vv = new LinkedHashMap<>(version.vv());
        vv.merge(replica, 1, Integer::sum);
        return new Version(vv, new TreeSet<>(items));
    }

    static Version reconcile(Version a, Version b, String replica,
                             BinaryOperator<Set<String>> merge) {
        String rel = vvCompare(a.vv(), b.vv());
        if (rel.equals("before") || rel.equals("equal")) return b;   // newer simply replaces
        if (rel.equals("after")) return a;
        // concurrent: keep both as siblings, merge, then step past both
        Map<String, Integer> mergedVv = new LinkedHashMap<>();
        for (String r : REPLICAS) mergedVv.put(r, Math.max(a.vv().get(r), b.vv().get(r)));
        mergedVv.merge(replica, 1, Integer::sum);
        return new Version(mergedVv, merge.apply(a.items(), b.items()));
    }

    static void cartDemo() {
        Version base = new Version(vv(3, 2),
                new TreeSet<>(Set.of("headphones", "phone case")));
        Set<String> withCharger = new TreeSet<>(base.items());
        withCharger.add("charger");
        Set<String> noHeadphones = new TreeSet<>(base.items());
        noHeadphones.remove("headphones");
        Version kabir = write(base, "mumbai", withCharger);           // [4, 2]
        Version sara = write(base, "london", noHeadphones);           // [3, 3]
        Set<String> removed = new TreeSet<>(base.items());
        removed.removeAll(sara.items());
        BinaryOperator<Set<String>> merge = (x, y) -> {               // keep adds, honour removes
            Set<String> out = new TreeSet<>(x);
            out.addAll(y);
            out.removeAll(removed);
            return out;
        };
        Version result = reconcile(kabir, sara, "mumbai", merge);
        System.out.printf("CART     %s vs %s: %s -> merged %s at %s%n",
                kabir.vv(), sara.vv(), vvCompare(kabir.vv(), sara.vv()),
                result.items(), result.vv());
    }

    // ---------------------------------------------------------------------
    // Spring Boot: run the demos at startup, and expose the compare rule over HTTP
    // ---------------------------------------------------------------------
    @GetMapping("/compare")
    public String compareOverHttp(@RequestParam int[] a, @RequestParam int[] b) {
        Map<String, Integer> x = new LinkedHashMap<>();
        Map<String, Integer> y = new LinkedHashMap<>();
        for (int i = 0; i < SERVERS.size(); i++) {
            x.put(SERVERS.get(i), a[i]);
            y.put(SERVERS.get(i), b[i]);
        }
        return compare(x, y);                 // e.g. /compare?a=2,0,0&b=2,2,0 -> before
    }

    @Override
    public void run(String... args) {
        wallClockProblem();
        lamportDemo();
        vectorDemo();
        cartDemo();
    }

    public static void main(String[] args) {
        SpringApplication.run(LogicalClocksApplication.class, args);
    }
}
