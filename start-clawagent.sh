#!/usr/bin/env sh
set -eu

PORT="${PORT:-17891}"
SKIP_FRONTEND_BUILD="${SKIP_FRONTEND_BUILD:-false}"
SKIP_SERVER_BUILD="${SKIP_SERVER_BUILD:-false}"
BACKGROUND="${BACKGROUND:-false}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-60}"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$SCRIPT_DIR"

log() {
  printf '[clawagent] %s\n' "$1"
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf '[clawagent] missing command: %s\n' "$1" >&2
    exit 1
  fi
}

port_in_use() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
    return $?
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltn | awk '{print $4}' | grep -Eq "[:.]$PORT$"
    return $?
  fi
  return 1
}

wait_health() {
  url="$1"
  timeout="$2"
  elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    if command -v curl >/dev/null 2>&1; then
      if curl -fsS "$url" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        return 0
      fi
    elif command -v wget >/dev/null 2>&1; then
      if wget -qO- "$url" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
        return 0
      fi
    else
      printf '[clawagent] curl/wget not found, skip health wait.\n' >&2
      return 2
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  return 1
}

need_cmd java
need_cmd mvn

if port_in_use; then
  printf '[clawagent] port %s is already in use. Set PORT=17892 or stop the existing process.\n' "$PORT" >&2
  exit 1
fi

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  printf '[clawagent] warning: DEEPSEEK_API_KEY is not set. The server can start, but the default chat model may fail.\n' >&2
fi
if [ -z "${SILICONFLOW_API_KEY:-}" ]; then
  printf '[clawagent] warning: SILICONFLOW_API_KEY is not set. Vector memory or SiliconFlow models may fail.\n' >&2
fi

if [ "$SKIP_FRONTEND_BUILD" != "true" ]; then
  need_cmd npm
  log "building admin frontend"
  (cd claw-agent-admin && npm run build)
fi

if [ "$SKIP_SERVER_BUILD" != "true" ]; then
  log "packaging server"
  mvn -pl claw-agent-server -am package -DskipTests
fi

JAR="claw-agent-server/target/claw-agent-server-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  printf '[clawagent] server jar not found: %s. Re-run without SKIP_SERVER_BUILD=true.\n' "$JAR" >&2
  exit 1
fi

log "admin: http://localhost:$PORT/admin/index.html"
log "health: http://localhost:$PORT/api/v1/health"

if [ "$BACKGROUND" = "true" ]; then
  mkdir -p logs
  OUT="logs/clawagent.out.log"
  ERR="logs/clawagent.err.log"
  log "starting server in background"
  nohup java -jar "$JAR" "--server.port=$PORT" >"$OUT" 2>"$ERR" &
  PID="$!"
  log "pid: $PID"
  log "logs: $OUT"
  log "waiting for health, timeout: ${HEALTH_TIMEOUT_SECONDS}s"
  if wait_health "http://localhost:$PORT/api/v1/health" "$HEALTH_TIMEOUT_SECONDS"; then
    log "server is healthy"
  else
    printf '[clawagent] server did not become healthy within %ss. Check logs: %s / %s\n' "$HEALTH_TIMEOUT_SECONDS" "$OUT" "$ERR" >&2
  fi
  exit 0
fi

exec java -jar "$JAR" "--server.port=$PORT"
