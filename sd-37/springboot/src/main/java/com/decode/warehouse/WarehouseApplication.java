/*
 * Video 37: Heartbeats & Gossip (Decode System Design)
 * Warehouse service. Failure detection is done by Kubernetes probes (see ../k8s) that call
 * Spring Boot Actuator's /actuator/health/liveness and /actuator/health/readiness.
 * Run: mvn spring-boot:run   then see README.md for the curl commands.
 */
package com.decode.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WarehouseApplication {
    public static void main(String[] args) {
        SpringApplication.run(WarehouseApplication.class, args);
    }
}
