# sd-39: code for Decode System Design #39

How Do Servers Agree When They Keep Crashing? (Raft & Paxos Explained)

All code for this video is in this folder (Java 17, Spring Boot 3, tested 2026-11-26).

| File | What it is |
|---|---|
| `springboot/` | Order log: Spring Boot 3 + Spring Kafka. Run `cd springboot && mvn spring-boot:run` (starts 3 real Kafka brokers in-process for the demo) |
| `springboot/src/main/java/com/decode/orderlog/problem/FireAndForgetProducer.java` | PROBLEM: acks=1 and "confirmed" without waiting |
| `springboot/src/main/java/com/decode/orderlog/OrderLogConfig.java` | SOLUTION: topic with 3 copies, min.insync.replicas 2, unclean leader election off |
| `springboot/src/main/java/com/decode/orderlog/OrderAssignmentService.java` | SOLUTION: "confirmed" only after the ack; error means retry with the same order ID |
| `springboot/src/main/resources/application.properties` | producer: acks=all, idempotence on, delivery timeout |
| `output.txt` | what the demo prints |

Production: remove `spring-kafka-test` and the embedded brokers in `main`, set `spring.kafka.bootstrap-servers` to your 3 brokers, and set the same values as broker defaults (default.replication.factor=3, min.insync.replicas=2, unclean.leader.election.enable=false).

## Output

```
PROBLEM  acks=1 + no wait: "confirmed" is returned before any other copy exists (see problem/FireAndForgetProducer)
SOLUTION topic order-assignments: 6 partitions, 3 copies each, min.insync.replicas=2, unclean.leader.election.enable=false
SOLUTION partition 0: leader broker 2, copies on [2, 0, 1], in-sync [2, 0, 1]
SOLUTION producer: acks=all, enable.idempotence=true
SOLUTION order-501 -> Pune acked (partition 3, offset 0) -> confirmed
SOLUTION order-502 -> Mumbai acked (partition 3, offset 1) -> confirmed
```
