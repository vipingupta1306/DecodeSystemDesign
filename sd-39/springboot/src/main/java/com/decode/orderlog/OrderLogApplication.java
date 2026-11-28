/*
 * Video 39: Raft & Paxos (Decode System Design)
 * PROBLEM:  the log leader said "confirmed" after writing only its own copy, then crashed.
 * SOLUTION: Kafka (consensus-backed replicated log): replication factor 3, min.insync.replicas 2,
 *           acks=all, idempotence on, unclean leader election off, reply only after the ack.
 * Run: mvn spring-boot:run   (starts a real 3-broker Kafka cluster in-process for the demo)
 */
package com.decode.orderlog;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import java.util.List;

@SpringBootApplication
public class OrderLogApplication implements CommandLineRunner {

    private final OrderAssignmentService orders;
    private final KafkaAdmin admin;

    public OrderLogApplication(OrderAssignmentService orders, KafkaAdmin admin) {
        this.orders = orders;
        this.admin = admin;
    }

    public static void main(String[] args) {
        // demo only: 3 real brokers in this process
        EmbeddedKafkaKraftBroker brokers = new EmbeddedKafkaKraftBroker(3, 1);
        brokers.afterPropertiesSet();
        System.setProperty("spring.kafka.bootstrap-servers", brokers.getBrokersAsString());
        int code = 1;
        try {
            code = SpringApplication.exit(SpringApplication.run(OrderLogApplication.class, args));
        } finally {
            brokers.destroy();
            System.exit(code);
        }
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("PROBLEM  acks=1 + no wait: \"confirmed\" is returned before any other copy exists (see problem/FireAndForgetProducer)");

        try (AdminClient ac = AdminClient.create(admin.getConfigurationProperties())) {
            TopicDescription d = null;
            for (int i = 0; i < 50 && d == null; i++) {               // demo: wait until all 3 brokers know the new topic
                try {
                    d = ac.describeTopics(List.of("order-assignments")).allTopicNames().get().get("order-assignments");
                    if (d.partitions().get(0).isr().size() < 3) { d = null; Thread.sleep(200); }
                } catch (Exception e) { Thread.sleep(200); }
            }
            Config c = null;
            for (int i = 0; i < 50 && c == null; i++) {
                try { c = ac.describeConfigs(List.of(new ConfigResource(ConfigResource.Type.TOPIC, "order-assignments")))
                        .all().get().values().iterator().next(); } catch (Exception e) { Thread.sleep(200); }
            }
            System.out.printf("SOLUTION topic %s: %d partitions, %d copies each, min.insync.replicas=%s, unclean.leader.election.enable=%s%n",
                    d.name(), d.partitions().size(), d.partitions().get(0).replicas().size(),
                    c.get("min.insync.replicas").value(), c.get("unclean.leader.election.enable").value());
            var p0 = d.partitions().get(0);
            System.out.printf("SOLUTION partition 0: leader broker %d, copies on %s, in-sync %s%n",
                    p0.leader().id(), p0.replicas().stream().map(n -> n.id()).toList(), p0.isr().stream().map(n -> n.id()).toList());
        }
        System.out.println("SOLUTION producer: acks=all, enable.idempotence=true");
        System.out.println("SOLUTION " + orders.assign("order-501", "Pune"));
        System.out.println("SOLUTION " + orders.assign("order-502", "Mumbai"));
    }
}
