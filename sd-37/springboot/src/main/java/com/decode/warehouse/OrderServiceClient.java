package com.decode.warehouse;

import org.springframework.web.client.RestClient;

/**
 * SOLUTION (order service side): no timeout logic, no "dead" list.
 * It calls the Kubernetes Service name; Kubernetes sends the call only to READY warehouse pods.
 * (Kept in this project so the whole example is in one place.)
 */
public class OrderServiceClient {

    private final RestClient client = RestClient.create("http://warehouse-service");   // the Kubernetes Service

    public String sendOrder(String orderId, String idempotencyKey) {
        return client.post()
                .uri("/orders?count=1")
                .header("Idempotency-Key", idempotencyKey)   // a retry never creates a second order
                .retrieve()
                .body(String.class);
    }
}
