package com.decode.orderlog;

import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class OrderAssignmentService {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public OrderAssignmentService(KafkaTemplate<String, String> kafka, @Value("${orderlog.topic}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    /**
     * SOLUTION: say "confirmed" ONLY after Kafka acknowledged the write (acks=all, min 2 copies).
     * The order ID is the key: a retry is de-duplicated by the idempotent producer and the warehouse.
     */
    public String assign(String orderId, String warehouse) {
        try {
            RecordMetadata m = kafka.send(topic, orderId, warehouse).get(10, TimeUnit.SECONDS).getRecordMetadata();
            return String.format("%s -> %s acked (partition %d, offset %d) -> confirmed", orderId, warehouse, m.partition(), m.offset());
        } catch (Exception e) {
            return orderId + " NOT confirmed (" + e.getClass().getSimpleName() + "), retry with the same order ID";
        }
    }
}
