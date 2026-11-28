package com.decode.warehouse;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WarehouseController {

    private final WarehouseQueue queue;
    private final ApplicationEventPublisher events;

    public WarehouseController(WarehouseQueue queue, ApplicationEventPublisher events) {
        this.queue = queue;
        this.events = events;
    }

    @PostMapping("/orders")
    public String accept(@RequestParam(defaultValue = "1") int count) {
        return "pending=" + queue.add(count);
    }

    @PostMapping("/packed")
    public String packed(@RequestParam(defaultValue = "1") int count) {
        return "pending=" + queue.packed(count);
    }

    /** SOLUTION: before maintenance, stop new traffic but stay alive (no restart, no moved orders). */
    @PostMapping("/admin/maintenance")
    public String maintenance(@RequestParam boolean on) {
        AvailabilityChangeEvent.publish(events, this,
                on ? ReadinessState.REFUSING_TRAFFIC : ReadinessState.ACCEPTING_TRAFFIC);
        return on ? "refusing new orders" : "accepting new orders";
    }
}
