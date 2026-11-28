package com.decode.warehouse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * SOLUTION: the warehouse itself says "do not send me new orders" when its queue is too long.
 * Bean name "warehouseQueueHealthIndicator" -> indicator name "warehouseQueue" (used in the readiness group).
 */
@Component("warehouseQueueHealthIndicator")
public class WarehouseQueueHealth implements HealthIndicator {

    private final WarehouseQueue queue;
    private final int maxPending;

    public WarehouseQueueHealth(WarehouseQueue queue, @Value("${warehouse.queue.max-pending}") int maxPending) {
        this.queue = queue;
        this.maxPending = maxPending;
    }

    @Override
    public Health health() {
        int pending = queue.pending();
        if (pending > maxPending) {
            return Health.outOfService().withDetail("pending", pending).withDetail("max", maxPending).build();
        }
        return Health.up().withDetail("pending", pending).build();
    }
}
