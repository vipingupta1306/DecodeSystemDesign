# sd-38: code for Decode System Design #38

What Happens When the Leader Dies? (Leader Election & Split Brain Explained)

All code for this video is in this folder (Java 17, Spring Boot 3, tested 2026-11-26).

| File | What it is |
|---|---|
| `springboot/` | Dispatcher: Spring Boot 3 + Apache Curator. Run `cd springboot && mvn spring-boot:run` (starts a real 5-server ZooKeeper ensemble in-process for the demo) |
| `springboot/src/main/java/com/decode/dispatch/problem/HighestIdElection.java` | PROBLEM: our own "highest ID wins" election |
| `springboot/src/main/java/com/decode/dispatch/Dispatcher.java` | SOLUTION: LeaderLatch, isLeader / notLeader, stop on SUSPENDED or LOST, term = latch number |
| `springboot/src/main/java/com/decode/dispatch/Warehouse.java` | SOLUTION: rejects orders with an older term |
| `springboot/src/main/java/com/decode/dispatch/DispatcherApplication.java` | the demo: leader, leader leaves, ZooKeeper loses its majority, comes back |
| `springboot/src/main/resources/application.properties` | session timeout 10 s, connection timeout 3 s, leader path |
| `output.txt` | what the demo prints (server numbers change from run to run) |

Production: remove `curator-test` from pom.xml and connect to your own 5 ZooKeeper servers.

## Output

```
PROBLEM  own election, side {1,2,3}: leader = server 3
PROBLEM  own election, side {4,5}  : leader = server 5  -> TWO LEADERS
SOLUTION ZooKeeper ensemble: 5 servers, majority 3
SOLUTION   server 4 is LEADER, term 0 -> start assigning orders
SOLUTION order-1001 from server 4 term 0 -> accepted
SOLUTION --- the leader goes away ---
SOLUTION   server 1 is LEADER, term 1 -> start assigning orders
SOLUTION order-1002 from server 1 term 1 -> accepted
SOLUTION order-1002 from the OLD leader term 0 -> REJECTED (old leader, highest term 1)
SOLUTION --- 3 of 5 ZooKeeper servers go down: no majority ---
SOLUTION   server 1 is no longer leader -> stop
SOLUTION anyone assigning without a majority? false
SOLUTION --- servers come back ---
SOLUTION   server 1 is LEADER, term 1 -> start assigning orders
SOLUTION order-1003 from server 1 term 1 -> accepted
SOLUTION warehouse packed [order-1001 from server 4, order-1002 from server 1, order-1003 from server 1] (each order once)
```
