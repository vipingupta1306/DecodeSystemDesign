/*
 * Video 38: Leader Election & Split Brain (Decode System Design)
 * PROBLEM:  our own "highest ID wins" election -> two leaders in a network split.
 * SOLUTION: ZooKeeper (majority) + Curator LeaderLatch; session timeout = lease; stop on SUSPENDED;
 *           latch number = term; the warehouse rejects a lower term.
 * Run: mvn spring-boot:run   (starts a real 5-server ZooKeeper ensemble in-process for the demo)
 */
package com.decode.dispatch;

import com.decode.dispatch.problem.HighestIdElection;
import org.apache.curator.test.InstanceSpec;
import org.apache.curator.test.TestingCluster;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SpringBootApplication
public class DispatcherApplication implements CommandLineRunner {

    @Value("${dispatcher.session-timeout-ms}") int sessionTimeoutMs;
    @Value("${dispatcher.connection-timeout-ms}") int connectionTimeoutMs;
    @Value("${dispatcher.leader-path}") String leaderPath;

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(DispatcherApplication.class, args)));
    }

    @Override
    public void run(String... args) throws Exception {
        // ---------------- PROBLEM ----------------
        System.out.println("PROBLEM  own election, side {1,2,3}: leader = server " + HighestIdElection.leader(Set.of(1, 2, 3)));
        System.out.println("PROBLEM  own election, side {4,5}  : leader = server " + HighestIdElection.leader(Set.of(4, 5)) + "  -> TWO LEADERS");

        // ---------------- SOLUTION ----------------
        try (TestingCluster zk = new TestingCluster(5)) {
            zk.start();
            System.out.println("SOLUTION ZooKeeper ensemble: 5 servers, majority 3");
            List<Dispatcher> ds = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                ds.add(new Dispatcher(i, zk.getConnectString(), leaderPath, sessionTimeoutMs, connectionTimeoutMs));
            }
            Dispatcher leader = waitForLeader(ds);
            Warehouse pune = new Warehouse();
            System.out.println("SOLUTION " + leader.assign(pune, "order-1001"));
            long oldTerm = leader.term();

            System.out.println("SOLUTION --- the leader goes away ---");
            leader.close();
            ds.remove(leader);
            Dispatcher next = waitForLeader(ds);
            System.out.println("SOLUTION " + next.assign(pune, "order-1002"));
            System.out.println("SOLUTION " + pune.accept(oldTerm, "order-1002 from the OLD leader"));

            System.out.println("SOLUTION --- 3 of 5 ZooKeeper servers go down: no majority ---");
            List<InstanceSpec> specs = new ArrayList<>(zk.getInstances());
            for (int i = 0; i < 3; i++) zk.killServer(specs.get(i));
            long until = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < until && ds.stream().anyMatch(Dispatcher::isAssigning)) Thread.sleep(100);
            System.out.println("SOLUTION anyone assigning without a majority? " + ds.stream().anyMatch(Dispatcher::isAssigning));

            System.out.println("SOLUTION --- servers come back ---");
            for (int i = 0; i < 3; i++) zk.restartServer(specs.get(i));
            Dispatcher again = waitForLeader(ds);
            System.out.println("SOLUTION " + again.assign(pune, "order-1003"));
            System.out.println("SOLUTION warehouse packed " + pune.packed() + " (each order once)");

            Dispatcher.shuttingDown = true;
            for (Dispatcher d : ds) d.close();
        }
    }

    private static Dispatcher waitForLeader(List<Dispatcher> ds) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            List<Dispatcher> leaders = ds.stream().filter(Dispatcher::isAssigning).toList();
            if (leaders.size() == 1) return leaders.get(0);
            Thread.sleep(100);
        }
        throw new IllegalStateException("no single leader");
    }
}
