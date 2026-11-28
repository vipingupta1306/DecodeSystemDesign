B=localhost:8080
run(){ echo "\$ $1"; eval "$1"; echo; echo; }
run "curl -s $B/actuator/health/liveness"
run "curl -s $B/actuator/health/readiness"
run "curl -s -X POST '$B/orders?count=5200'"
run "curl -s -w ' (HTTP %{http_code})' $B/actuator/health/readiness"
run "curl -s $B/actuator/health/liveness"
run "curl -s -X POST '$B/packed?count=1000'"
run "curl -s -w ' (HTTP %{http_code})' $B/actuator/health/readiness"
run "curl -s -X POST '$B/admin/maintenance?on=true'"
run "curl -s -w ' (HTTP %{http_code})' $B/actuator/health/readiness"
run "curl -s $B/actuator/health/liveness"
run "curl -s -X POST '$B/admin/maintenance?on=false'"
run "curl -s -w ' (HTTP %{http_code})' $B/actuator/health/readiness"
