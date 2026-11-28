# sd-37: code for Decode System Design #37

How Do You Know a Server Is Dead, Not Just Slow? (Heartbeats & Gossip Explained)

All code for this video is in this folder (Java 17, Spring Boot 3, tested 2026-11-26).

| File | What it is |
|---|---|
| `springboot/` | Warehouse service: Spring Boot 3 + Actuator. Run `cd springboot && mvn spring-boot:run`, then `bash ../demo.sh` |
| `springboot/src/main/java/com/decode/warehouse/problem/NaiveTimeoutRouter.java` | PROBLEM: our own 3 s timeout that moves all orders |
| `springboot/src/main/java/com/decode/warehouse/WarehouseQueueHealth.java` | SOLUTION: readiness check on the queue (over 5,000 pending = out of service) |
| `springboot/src/main/java/com/decode/warehouse/WarehouseController.java` | orders, packed, and maintenance mode (REFUSING_TRAFFIC) |
| `springboot/src/main/java/com/decode/warehouse/OrderServiceClient.java` | SOLUTION, order side: call the Kubernetes Service name, no timeout code |
| `springboot/src/main/resources/application.properties` | probes on, liveness and readiness groups |
| `k8s/warehouse-deployment.yaml` | startup, readiness and liveness probes + the Service |
| `consul/application-consul.properties` | the same idea on VMs with Consul |
| `demo.sh`, `output.txt` | the curl calls and what they print |

## Output

```
$ curl -s localhost:8080/actuator/health/liveness
{"status":"UP","components":{"livenessState":{"status":"UP"}}}

$ curl -s localhost:8080/actuator/health/readiness
{"status":"UP","components":{"readinessState":{"status":"UP"},"warehouseQueue":{"status":"UP","details":{"pending":0}}}}

$ curl -s -X POST 'localhost:8080/orders?count=5200'
pending=5200

$ curl -s -w ' (HTTP %{http_code})' localhost:8080/actuator/health/readiness
{"status":"OUT_OF_SERVICE","components":{"readinessState":{"status":"UP"},"warehouseQueue":{"status":"OUT_OF_SERVICE","details":{"pending":5200,"max":5000}}}} (HTTP 503)

$ curl -s localhost:8080/actuator/health/liveness
{"status":"UP","components":{"livenessState":{"status":"UP"}}}

$ curl -s -X POST 'localhost:8080/packed?count=1000'
pending=4200

$ curl -s -w ' (HTTP %{http_code})' localhost:8080/actuator/health/readiness
{"status":"UP","components":{"readinessState":{"status":"UP"},"warehouseQueue":{"status":"UP","details":{"pending":4200}}}} (HTTP 200)

$ curl -s -X POST 'localhost:8080/admin/maintenance?on=true'
refusing new orders

$ curl -s -w ' (HTTP %{http_code})' localhost:8080/actuator/health/readiness
{"status":"OUT_OF_SERVICE","components":{"readinessState":{"status":"OUT_OF_SERVICE"},"warehouseQueue":{"status":"UP","details":{"pending":4200}}}} (HTTP 503)

$ curl -s localhost:8080/actuator/health/liveness
{"status":"UP","components":{"livenessState":{"status":"UP"}}}

$ curl -s -X POST 'localhost:8080/admin/maintenance?on=false'
accepting new orders

$ curl -s -w ' (HTTP %{http_code})' localhost:8080/actuator/health/readiness
{"status":"UP","components":{"readinessState":{"status":"UP"},"warehouseQueue":{"status":"UP","details":{"pending":4200}}}} (HTTP 200)
```
