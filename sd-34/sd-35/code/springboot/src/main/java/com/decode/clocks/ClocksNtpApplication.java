/*
 * Video 35: Clock Skew & NTP (Decode System Design)
 * Language: Java 17 + Spring Boot 3. Run: mvn spring-boot:run
 *
 * PROBLEM 1: last write wins trusts server timestamps; Mumbai's clock is 300 ms ahead.
 * PROBLEM 2: measuring a duration with the wall clock goes negative when NTP steps it back.
 * SOLUTION:  NTP offset math, slew instead of step, the monotonic clock (System.nanoTime)
 *            for durations, and an uncertainty window (TrueTime-style commit wait).
 */
package com.decode.clocks;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@SpringBootApplication
public class ClocksNtpApplication implements CommandLineRunner {

    // ---------------------------------------------------------------------
    // A simulated server clock: real time + offset + drift (ppm)
    // ---------------------------------------------------------------------
    record ServerClock(double offsetMs, double driftPpm) {
        double nowMs(double realMs) {
            double drift = realMs * driftPpm / 1_000_000;
            return realMs + offsetMs + drift;
        }
    }

    record Write(String op, double ts) {}

    // ---------------------------------------------------------------------
    // PROBLEM 1: last write wins with a skewed clock
    // ---------------------------------------------------------------------
    static void lwwProblem() {
        ServerClock mumbai = new ServerClock(+300, 0);   // 300 ms ahead
        ServerClock london = new ServerClock(0, 0);
        Write kabir = new Write("add charger", mumbai.nowMs(300));        // real time 300
        Write sara = new Write("remove headphones", london.nowMs(500));   // real time 500 (later!)
        Write winner = List.of(kabir, sara).stream()
                .max(Comparator.comparingDouble(Write::ts)).get();
        System.out.printf("PROBLEM 1: Kabir ts=%.0f, Sara ts=%.0f -> LWW keeps '%s' although Sara wrote later%n",
                kabir.ts(), sara.ts(), winner.op());
    }

    // ---------------------------------------------------------------------
    // Drift: 20 ppm for one day
    // ---------------------------------------------------------------------
    static double driftPerDay(double ppm) {
        long seconds = 86_400;
        return seconds * ppm / 1_000_000;                // seconds of error per day
    }

    // ---------------------------------------------------------------------
    // SOLUTION A: NTP offset and delay from four timestamps
    // t1: client sends (client clock)    t2: server receives (server clock)
    // t3: server replies (server clock)  t4: client receives (client clock)
    // offset < 0 means the client's clock is AHEAD of the server.
    // ---------------------------------------------------------------------
    record Sample(double offset, double delay) {}

    static Sample ntpOffsetDelay(double t1, double t2, double t3, double t4) {
        double delay = (t4 - t1) - (t3 - t2);
        double offset = ((t2 - t1) + (t3 - t4)) / 2;
        return new Sample(offset, delay);
    }

    /** NTP trusts the sample with the smallest round trip (least room for error). */
    static Sample bestSample(List<double[]> samples) {
        return samples.stream()
                .map(s -> ntpOffsetDelay(s[0], s[1], s[2], s[3]))
                .min(Comparator.comparingDouble(Sample::delay)).get();
    }

    // ---------------------------------------------------------------------
    // SOLUTION B: slew (change speed) instead of step (jump)
    // At 500 ppm the clock gains/loses 0.5 ms per real second.
    // ---------------------------------------------------------------------
    static double slewSecondsNeeded(double offsetMs, double maxPpm) {
        double msPerSecond = maxPpm / 1000;
        return Math.abs(offsetMs) / msPerSecond;
    }

    // ---------------------------------------------------------------------
    // PROBLEM 2 and SOLUTION C: wall clock vs monotonic clock
    // ---------------------------------------------------------------------
    /** Stands in for System.currentTimeMillis(); NTP may step it. */
    static class FakeWallClock {
        double t;
        FakeWallClock(double start) { this.t = start; }
        double time() { return t; }
        void advance(double s) { t += s; }
        void ntpStep(double s) { t += s; }
    }

    static void durationProblem() {
        FakeWallClock wall = new FakeWallClock(36_005.0);   // 10:00:05
        double start = wall.time();
        wall.advance(0.2);                                  // request runs 0.2 s ...
        wall.ntpStep(-1.0);                                 // ... NTP steps the clock back 1 s
        System.out.printf("PROBLEM 2: duration with wall clock = %+.1f s%n", wall.time() - start);
    }

    /** SOLUTION C: durations, timeouts, leases -> monotonic clock. */
    static double timedRequest(Runnable work) {
        long start = System.nanoTime();                     // never jumps, only moves forward
        work.run();
        return (System.nanoTime() - start) / 1e9;
    }

    static boolean leaseValid(long acquiredAtNanos, double leaseS) {
        return (System.nanoTime() - acquiredAtNanos) / 1e9 < leaseS;
    }

    // ---------------------------------------------------------------------
    // SOLUTION D: when order matters, wait out the clock uncertainty (TrueTime idea)
    // The clock says "now is in [now - eps, now + eps]". Use the latest bound as the
    // commit timestamp and make the write visible only after 2 * eps.
    // ---------------------------------------------------------------------
    record Commit(long commitTs, long waitMs) {}

    static Commit commitWithWait(long nowMs, long epsilonMs) {
        long commitTs = nowMs + epsilonMs;
        long waitMs = 2 * epsilonMs;
        return new Commit(commitTs, waitMs);
    }

    // ---------------------------------------------------------------------
    // Spring Boot wiring: a service plus a REST endpoint to try NTP math by hand
    // ---------------------------------------------------------------------
    @Service
    static class NtpService {
        Sample estimate(double t1, double t2, double t3, double t4) {
            return ntpOffsetDelay(t1, t2, t3, t4);
        }
    }

    @RestController
    static class NtpController {
        private final NtpService ntp;
        NtpController(NtpService ntp) { this.ntp = ntp; }

        // GET /ntp?t1=1000&t2=725&t3=726&t4=1051
        @GetMapping("/ntp")
        Sample ntp(@RequestParam double t1, @RequestParam double t2,
                   @RequestParam double t3, @RequestParam double t4) {
            return ntp.estimate(t1, t2, t3, t4);
        }
    }

    @Override
    public void run(String... args) throws Exception {
        lwwProblem();
        System.out.printf("Drift at 20 ppm: %.2f s per day%n", driftPerDay(20));
        Sample s = ntpOffsetDelay(1000, 725, 726, 1051);
        System.out.printf("SOLUTION A: delay = %.0f ms, offset = %+.0f ms (Mumbai is 300 ms ahead)%n", s.delay(), s.offset());
        Sample b = bestSample(List.of(new double[]{1000, 725, 726, 1051},
                new double[]{2000, 1745, 1746, 2101}, new double[]{3000, 2712, 2713, 3027}));
        System.out.printf("           best of 3 samples: delay %.0f ms, offset %+.1f ms%n", b.delay(), b.offset());
        System.out.printf("SOLUTION B: slewing 300 ms at 500 ppm takes %.0f minutes%n", slewSecondsNeeded(300, 500) / 60);
        durationProblem();
        double took = timedRequest(() -> {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        System.out.printf("SOLUTION C: monotonic duration = %.2f s | lease valid: %b%n", took, leaseValid(System.nanoTime(), 5.0));
        Commit c = commitWithWait(10_000_000_500L, 4);
        System.out.printf("SOLUTION D: commit timestamp %d, wait %d ms before the write is visible%n", c.commitTs(), c.waitMs());
        System.exit(0);
    }

    public static void main(String[] args) {
        SpringApplication.run(ClocksNtpApplication.class, args);
    }
}
