package com.decode.warehouse.problem;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROBLEM (what we had before): our own 3 s timeout, and "dead" means "move ALL its orders".
 * On sale day a 4 s gap marks a live warehouse dead and 2,000 orders get packed twice.
 * Delete this class: Kubernetes probes replace it.
 */
public class NaiveTimeoutRouter {

    private static final long TIMEOUT_MS = 3000;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, List<String>> ordersByWarehouse = new ConcurrentHashMap<>();

    public void heartbeat(String warehouse, long nowMs) {
        lastSeen.put(warehouse, nowMs);
    }

    public boolean isAlive(String warehouse, long nowMs) {
        return nowMs - lastSeen.getOrDefault(warehouse, 0L) < TIMEOUT_MS;   // one fixed number for every network mood
    }

    public void checkAndMove(String warehouse, String backup, long nowMs) {
        if (!isAlive(warehouse, nowMs)) {
            List<String> moved = ordersByWarehouse.remove(warehouse);        // moves orders it may already be packing!
            if (moved != null) ordersByWarehouse.computeIfAbsent(backup, k -> new java.util.ArrayList<>()).addAll(moved);
        }
    }
}
