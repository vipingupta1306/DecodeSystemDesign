package com.decode.warehouse;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/** Orders accepted but not packed yet. */
@Component
public class WarehouseQueue {
    private final AtomicInteger pending = new AtomicInteger();

    public int add(int n) { return pending.addAndGet(n); }
    public int packed(int n) { return pending.updateAndGet(p -> Math.max(0, p - n)); }
    public int pending() { return pending.get(); }
}
