package com.decode.dispatch;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One dispatcher server. Curator's LeaderLatch does the election; we only react to it. */
public class Dispatcher implements AutoCloseable {

    static volatile boolean shuttingDown = false;   // demo only: quiet shutdown
    final int id;
    private final CuratorFramework client;
    private final LeaderLatch latch;
    private final AtomicBoolean assigning = new AtomicBoolean(false);
    private final AtomicLong term = new AtomicLong(-1);

    public Dispatcher(int id, String zkConnect, String path, int sessionTimeoutMs, int connectionTimeoutMs) throws Exception {
        this.id = id;
        this.client = CuratorFrameworkFactory.newClient(zkConnect, sessionTimeoutMs, connectionTimeoutMs,
                new ExponentialBackoffRetry(500, 3));

        // connection trouble -> stop at once, do not wait for the session to be LOST
        client.getConnectionStateListenable().addListener((c, state) -> {
            if (state == ConnectionState.SUSPENDED || state == ConnectionState.LOST) {
                if (assigning.getAndSet(false)) {
                    System.out.printf("SOLUTION   server %d: connection %s -> stop assigning orders%n", id, state);
                }
            }
        });
        client.start();

        this.latch = new LeaderLatch(client, path, "server-" + id);
        latch.addListener(new LeaderLatchListener() {
            @Override public void isLeader() {
                if (shuttingDown) return;
                term.set(latchNumber());
                assigning.set(true);
                System.out.printf("SOLUTION   server %d is LEADER, term %d -> start assigning orders%n", id, term.get());
            }
            @Override public void notLeader() {
                assigning.set(false);
                System.out.printf("SOLUTION   server %d is no longer leader -> stop%n", id);
            }
        });
        latch.start();
    }

    /** The latch node is sequential (.../latch-0000000007): a newer leader always has a bigger number = our term. */
    private long latchNumber() {
        String p = latch.getOurPath();
        return Long.parseLong(p.substring(p.lastIndexOf('-') + 1));
    }

    public boolean isAssigning() { return assigning.get(); }
    public long term() { return term.get(); }

    public String assign(Warehouse w, String order) {
        if (!assigning.get()) return "server " + id + " is not leader, did not assign " + order;
        return w.accept(term.get(), order + " from server " + id);
    }

    @Override public void close() throws Exception {
        latch.close();
        client.close();
    }
}
