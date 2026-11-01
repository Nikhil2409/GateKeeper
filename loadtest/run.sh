#!/usr/bin/env bash
# Runs the Gatekeeper load tests (k6 in Docker) against the full docker-compose stack.
# bash port of run.ps1 for macOS and Linux (works with the bash 3.2 that ships with macOS).
#
#   ./loadtest/run.sh                       every test, in order (about 40 minutes)
#   ./loadtest/run.sh baseline              one test
#   MAX_RATE=1000 ./loadtest/run.sh throughput
#   CHAOS_SERVICES="redis" ./loadtest/run.sh chaos
#
# Tests: baseline throughput scaling ratelimit hotreload failover chaos
# Results: loadtest/results/<test>.json, <test>.csv.gz, machine.txt, and <test>-prometheus.json for the outage runs.

set -u

TEST="${1:-all}"
MAX_RATE="${MAX_RATE:-2000}"                                  # highest rate the throughput test climbs to
STEADY_RATE="${STEADY_RATE:-200}"                             # requests per second while something is broken
CHAOS_SERVICES="${CHAOS_SERVICES:-kafka redis api-management-service}"

case "$TEST" in
  all|baseline|throughput|scaling|ratelimit|hotreload|failover|chaos) ;;
  *) echo "Unknown test '$TEST'" >&2; exit 2 ;;
esac

# Docker Desktop on macOS does not always put the CLI on PATH
if ! command -v docker >/dev/null 2>&1 && [ -x /Applications/Docker.app/Contents/Resources/bin/docker ]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS="$ROOT/loadtest/results"
COMPOSE=(docker compose -f "$ROOT/docker-compose.yml" -f "$ROOT/loadtest/docker-compose.loadtest.yml")
mkdir -p "$RESULTS"
cd "$ROOT" || exit 1

compose() { "${COMPOSE[@]}" "$@" || { echo "docker compose $* failed" >&2; exit 1; }; }

write_machine_info() {
  {
    echo "date:        $(date +%Y-%m-%dT%H:%M:%S)"
    echo "cpu:         $(sysctl -n machdep.cpu.brand_string 2>/dev/null || grep -m1 'model name' /proc/cpuinfo) ($(sysctl -n hw.physicalcpu 2>/dev/null || nproc) cores / $(sysctl -n hw.logicalcpu 2>/dev/null || nproc) threads)"
    if command -v sysctl >/dev/null 2>&1 && sysctl -n hw.memsize >/dev/null 2>&1; then
      echo "ram:         $(awk -v b="$(sysctl -n hw.memsize)" 'BEGIN { printf "%.1f GB", b / 1073741824 }')"
      echo "os:          $(sw_vers -productName) $(sw_vers -productVersion) ($(uname -m))"
    else
      echo "ram:         $(awk '/MemTotal/ { printf "%.1f GB", $2 / 1048576 }' /proc/meminfo)"
      echo "os:          $(uname -sr) ($(uname -m))"
    fi
    echo "docker vm:   $(awk -v b="$(docker info --format '{{.MemTotal}}')" 'BEGIN { printf "%.1f GB", b / 1073741824 }'), $(docker info --format '{{.NCPU}}') CPUs"
    echo "docker:      $(docker version --format '{{.Server.Version}}')"
    echo "note:        k6, the gateways and every dependency share this one machine"
  } > "$RESULTS/machine.txt"
}

# Stack up with the load-test additions (the loadtest-idp container writes the JWKS and tokens)
initialize_stack() {
  compose up -d
  echo 'Waiting for the gateway...'
  for _ in $(seq 1 60); do
    if curl -sf -m 3 http://localhost:8080/_gateway/health | grep -q '"status":"UP"'; then return; fi
    sleep 5
  done
  echo 'The gateway did not become ready' >&2; exit 1
}

# invoke_k6 <script> <name> [KEY=VALUE ...]
invoke_k6() {
  local script="$1" name="$2"; shift 2
  local env_args=(-e "RESULT_NAME=$name") kv
  for kv in "$@"; do env_args+=(-e "$kv"); done
  printf '\n=== %s (%s)\n' "$name" "$script"
  "${COMPOSE[@]}" run --rm --name "loadtest-k6-$name" "${env_args[@]}" k6 run --out "csv=/results/$name.csv.gz" "/scripts/$script"
  local rc=$?
  [ $rc -ne 0 ] && echo "WARNING: $name ended with exit code $rc (a threshold failed or the run aborted; see the summary)" >&2
  return 0
}

# Runs a script in the background and stops <service> 60 s into the measured part, starting it again 60 s later
# invoke_k6_with_outage <script> <name> <service> [KEY=VALUE ...]
invoke_k6_with_outage() {
  local script="$1" name="$2" service="$3"; shift 3
  local container="loadtest-k6-$name" env_args=(-e "RESULT_NAME=$name") kv
  for kv in "$@"; do env_args+=(-e "$kv"); done
  printf '\n=== %s (%s, %s stopped from 60 s to 120 s)\n' "$name" "$script" "$service"
  "${COMPOSE[@]}" run --rm --name "$container" "${env_args[@]}" k6 run --out "csv=/results/$name.csv.gz" "/scripts/$script" 2>&1 &
  local job=$!

  for _ in $(seq 1 120); do
    docker logs "$container" 2>&1 | grep -q 'LOADTEST_STARTED' && break
    sleep 1
  done
  sleep 60
  echo "  stopping $service"; compose stop "$service"
  sleep 60
  echo "  starting $service"; compose start "$service"
  wait "$job"
  save_prometheus_snapshot "$name"
}

# What the gateways reported during the outage: dropped traffic events, open circuits, outcomes
save_prometheus_snapshot() {
  local name="$1" out="$RESULTS/$1-prometheus.json" first=1 key query result
  prom_query() {
    curl -sf -m 5 -G http://localhost:9090/api/v1/query --data-urlencode "query=$1" || echo '{"error":"query failed"}'
  }
  {
    echo '{'
    while IFS='|' read -r key query; do
      [ $first -eq 0 ] && echo ','
      first=0
      printf '  "%s": %s' "$key" "$(prom_query "$query")"
    done <<'EOF'
traffic_events_dropped|sum(increase(gateway_traffic_events_dropped_total[5m]))
traffic_events_queued_max|max(max_over_time(gateway_traffic_events_queued[5m]))
requests_by_outcome|sum by (outcome, status) (increase(gateway_requests_seconds_count{organization=~"lt-.*"}[5m]))
requests_by_instance|sum by (instance) (increase(gateway_requests_seconds_count{organization=~"lt-.*"}[5m]))
EOF
    echo
    echo '}'
  } > "$out"
}

# An overloaded run can leave route circuit breakers open (30 s); the next run must not start into them.
wait_circuits_closed() { sleep 35; }

wait_gateways() {
  local up i
  for _ in $(seq 1 60); do
    up=0
    for i in 1 2 3; do
      # ready = configuration loaded from Kafka (the JVM being up is not enough)
      docker exec "gatekeeper-gateway-$i" curl -sf localhost:8080/_gateway/health 2>/dev/null | grep -q '"status":"UP"' && up=$((up + 1))
    done
    [ $up -eq 3 ] && return
    sleep 3
  done
  echo 'Not all gateway instances came back' >&2; exit 1
}

should_run() { [ "$TEST" = all ] || [ "$TEST" = "$1" ]; }

write_machine_info
initialize_stack

should_run baseline && invoke_k6 baseline.js baseline
should_run throughput && invoke_k6 throughput.js throughput "MAX_RATE=$MAX_RATE"
if should_run scaling; then
  # one instance, addressed directly (no nginx), then all three behind nginx
  invoke_k6 throughput.js scaling-1-instance "MAX_RATE=$MAX_RATE" "GATEWAY_URL=http://smart-gateway-1:8080"
  wait_circuits_closed
  invoke_k6 throughput.js scaling-3-instances "MAX_RATE=$MAX_RATE"
fi
should_run ratelimit && invoke_k6 rate-limit.js rate-limit
should_run hotreload && invoke_k6 hot-reload.js hot-reload
if should_run failover; then
  invoke_k6_with_outage resilience.js failover-gateway-2 smart-gateway-2 "RATE=$STEADY_RATE"
  wait_gateways
fi
if should_run chaos; then
  for service in $CHAOS_SERVICES; do
    invoke_k6_with_outage resilience.js "chaos-$service" "$service" "RATE=$STEADY_RATE"
    wait_gateways
    wait_circuits_closed
  done
fi
printf '\nResults in %s\n' "$RESULTS"
