package com.decode.orderlog.problem;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Properties;

/**
 * PROBLEM: acks=1 (only the leader wrote it) and we do not even wait for the result.
 * The leader crashes before the other copies have the record -> a "confirmed" order vanishes.
 */
public class FireAndForgetProducer {

    private final KafkaProducer<String, String> producer;

    public FireAndForgetProducer(String bootstrap) {
        Properties p = new Properties();
        p.put("bootstrap.servers", bootstrap);
        p.put("acks", "1");                                  // leader only
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(p);
    }

    public String assign(String orderId, String warehouse) {
        producer.send(new ProducerRecord<>("order-assignments", orderId, warehouse));   // no wait
        return "confirmed";                                  // said before anyone has a safe copy
    }
}
