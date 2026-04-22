#!/bin/bash

# ============================================
# Watchtower Agent
# ============================================
# 설치 전에 /etc/watchtower-agent.env 를 먼저 만들어야 합니다 (install 시 템플릿
# 생성). 시크릿은 이 파일(chmod 600)에서만 읽고, 스크립트에는 평문으로 두지
# 않습니다.
#
# 사용법:
#   1) sudo ./watchtower-agent.sh install     # 템플릿 env 생성
#   2) sudo vi /etc/watchtower-agent.env      # 값 채우기
#   3) sudo systemctl restart watchtower-agent
#   4) 로그: journalctl -u watchtower-agent -f
#
#   수동 실행 (디버깅):  sudo ./watchtower-agent.sh run
# ============================================

# =========== [기본값 / 수집 설정] ==========
# 시크릿/접속정보는 아래 ENV_FILE 에서 로드. 수집 파라미터만 여기 유지.
WATCH_PROCS_DEFAULT="java,python,node"
INTERVAL_DEFAULT=5
AGENT_PORT_DEFAULT=19090
IGNORE_PATH_REGEX_DEFAULT="^/api/v4/jobs/request|^/api/v4/runners|^/-/metrics|^/health|^/healthz|^/ping|/info/refs|/git-upload-pack|/git-receive-pack"
ACCESS_LOGS_DEFAULT="/var/log/nginx/access.log"
CERT_PATHS_DEFAULT="auto"
CERT_CHECK_INTERVAL_DEFAULT=300
SLOW_QUERY_LOG_DEFAULT=""
SLOW_QUERY_MAX_PER_CYCLE_DEFAULT=100

# 큐/타임아웃 상수
QUEUE_MAX_FILES=200              # 최대 보관 파일 수 (초과 시 오래된 것부터 삭제)
QUEUE_DRAIN_PER_TICK=20          # 한 틱에 재전송 시도 수
SEND_CONNECT_TIMEOUT=3
SEND_MAX_TIME=5

INSTALL_DIR="/opt/watchtower-agent"
SERVICE_FILE="/etc/systemd/system/watchtower-agent.service"
ENV_FILE="/etc/watchtower-agent.env"
STATE_DIR="/var/lib/watchtower-agent"
OFFSET_DIR="$STATE_DIR/offsets"
QUEUE_DIR="$STATE_DIR/queue"
# ============================================

load_env_file() {
    if [ -r "$ENV_FILE" ]; then
        set -a
        # shellcheck disable=SC1090
        . "$ENV_FILE"
        set +a
    fi

    # Defaults for non-secret settings
    WATCH_PROCS="${WATCH_PROCS:-$WATCH_PROCS_DEFAULT}"
    INTERVAL="${INTERVAL:-$INTERVAL_DEFAULT}"
    AGENT_PORT="${AGENT_PORT:-$AGENT_PORT_DEFAULT}"
    IGNORE_PATH_REGEX="${IGNORE_PATH_REGEX:-$IGNORE_PATH_REGEX_DEFAULT}"
    ACCESS_LOGS="${ACCESS_LOGS:-$ACCESS_LOGS_DEFAULT}"
    CERT_PATHS="${CERT_PATHS:-$CERT_PATHS_DEFAULT}"
    CERT_CHECK_INTERVAL="${CERT_CHECK_INTERVAL:-$CERT_CHECK_INTERVAL_DEFAULT}"
    SLOW_QUERY_LOG="${SLOW_QUERY_LOG:-$SLOW_QUERY_LOG_DEFAULT}"
    SLOW_QUERY_MAX_PER_CYCLE="${SLOW_QUERY_MAX_PER_CYCLE:-$SLOW_QUERY_MAX_PER_CYCLE_DEFAULT}"
}

# ============================================
# Install 모드
# ============================================
cmd_install() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "install 은 root 권한이 필요합니다. sudo 로 실행하세요."
        exit 1
    fi
    if ! command -v openssl >/dev/null 2>&1; then
        echo "openssl 이 필요합니다 (HMAC 서명용). 설치 후 다시 시도하세요."
        exit 1
    fi

    local SELF_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

    mkdir -p "$INSTALL_DIR" "$STATE_DIR" "$OFFSET_DIR" "$QUEUE_DIR"
    chmod 750 "$STATE_DIR"
    cp "$SELF_PATH" "$INSTALL_DIR/watchtower-agent.sh"
    chmod +x "$INSTALL_DIR/watchtower-agent.sh"

    if [ ! -f "$ENV_FILE" ]; then
        umask 077
        cat > "$ENV_FILE" <<'ENVEOF'
# /etc/watchtower-agent.env
#
# 아래 값을 서버에 맞게 채우세요. chmod 600 (root 전용).
# systemd 가 이 파일을 EnvironmentFile 로 읽어서 에이전트에 주입합니다.

# --- 필수 ---
WATCHTOWER_URL="https://<YOUR_CENTRAL_URL>"
HOST_ID="server-a"
DISPLAY_NAME="Server A"
# HMAC 시크릿 (중앙 서버 application.yml 의 watchtower.security.agents[].hmac-secret 와 동일)
# 생성 예:  openssl rand -hex 32
HMAC_SECRET="<FILL_WITH_openssl_rand_hex_32>"

# --- 선택: 레거시 싱글 API 키 모드 (HMAC_SECRET 가 비어있을 때만 사용) ---
# 중앙 서버 watchtower.security.allow-legacy-api-key=true 일 때만 동작.
# 운영에서는 비워두고 HMAC 사용 권장.
API_KEY=""

# --- 수집 파라미터 (선택, 기본값 사용하려면 주석) ---
# WATCH_PROCS="java,python,node"
# INTERVAL=5
# ACCESS_LOGS="/var/log/nginx/access.log"
# CERT_PATHS="auto"
# SLOW_QUERY_LOG=""
ENVEOF
        chmod 600 "$ENV_FILE"
        echo "==> Created template $ENV_FILE (chmod 600)"
        echo "==> 값 채운 뒤 'sudo systemctl restart watchtower-agent' 하세요."
    else
        echo "==> $ENV_FILE already exists — leaving untouched."
    fi

    if ! validate_env_for_install; then
        echo ""
        echo "설정이 불완전합니다. $ENV_FILE 을 수정한 뒤 다시 실행하세요."
        echo "systemd unit 은 생성/활성화되지만, 유효값 채우기 전까지는 재시작하지 않습니다."
        write_service_unit
        systemctl daemon-reload
        systemctl enable watchtower-agent >/dev/null 2>&1
        exit 2
    fi

    echo "==> Installing..."
    echo "    URL      : ${WATCHTOWER_URL}"
    echo "    HOST_ID  : ${HOST_ID}"
    echo "    DISPLAY  : ${DISPLAY_NAME}"
    echo "    Auth     : $( [ -n "$HMAC_SECRET" ] && echo HMAC || echo 'legacy API key' )"
    echo ""

    write_service_unit
    systemctl daemon-reload
    systemctl enable watchtower-agent
    systemctl restart watchtower-agent

    echo "==> Done! Status:"
    systemctl status watchtower-agent --no-pager
}

write_service_unit() {
    cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Watchtower Metrics Agent
After=network.target

[Service]
Type=simple
EnvironmentFile=-${ENV_FILE}
ExecStart=${INSTALL_DIR}/watchtower-agent.sh run
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
}

validate_env_for_install() {
    load_env_file
    local ok=true

    if [ -z "$WATCHTOWER_URL" ] || [[ "$WATCHTOWER_URL" == *"<YOUR_CENTRAL_URL>"* ]]; then
        echo "  [!] WATCHTOWER_URL 미설정 / placeholder"; ok=false
    fi
    if [ -z "$HOST_ID" ]; then
        echo "  [!] HOST_ID 비어있음"; ok=false
    elif ! [[ "$HOST_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
        echo "  [!] HOST_ID 는 영숫자/./_/- 만 허용"; ok=false
    fi
    if [ -z "$HMAC_SECRET" ] && [ -z "$API_KEY" ]; then
        echo "  [!] HMAC_SECRET 또는 API_KEY 중 하나는 필요"; ok=false
    fi
    if [ -n "$HMAC_SECRET" ] && [[ "$HMAC_SECRET" == *"<FILL_WITH_openssl_rand_hex_32>"* ]]; then
        echo "  [!] HMAC_SECRET 이 placeholder"; ok=false
    fi
    if [ -n "$HMAC_SECRET" ] && [ ${#HMAC_SECRET} -lt 32 ]; then
        echo "  [!] HMAC_SECRET 길이 부족 (>=32 문자 필요)"; ok=false
    fi
    $ok
}

# ============================================
# Run 모드 (수집 & Push 루프)
# ============================================
cmd_run() {
    load_env_file

    if [ -z "$WATCHTOWER_URL" ] || [[ "$WATCHTOWER_URL" == *"<YOUR_CENTRAL_URL>"* ]]; then
        echo "[FATAL] WATCHTOWER_URL not configured — check $ENV_FILE"; exit 2
    fi
    if [ -z "$HOST_ID" ]; then
        echo "[FATAL] HOST_ID not configured — check $ENV_FILE"; exit 2
    fi
    if [ -z "$HMAC_SECRET" ] && [ -z "$API_KEY" ]; then
        echo "[FATAL] need either HMAC_SECRET or API_KEY — check $ENV_FILE"; exit 2
    fi
    if [ -n "$HMAC_SECRET" ] && ! command -v openssl >/dev/null 2>&1; then
        echo "[FATAL] openssl required for HMAC signing"; exit 2
    fi

    mkdir -p "$STATE_DIR" "$OFFSET_DIR" "$QUEUE_DIR" 2>/dev/null

    local LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
    local AGENT_URL="http://${LOCAL_IP}:${AGENT_PORT}"
    local REPORT_URL="${WATCHTOWER_URL%/}/api/agent/report"

    echo "============================================"
    echo " Watchtower Agent"
    echo " Host ID    : ${HOST_ID}"
    echo " Display    : ${DISPLAY_NAME}"
    echo " Target     : ${REPORT_URL}"
    echo " Interval   : ${INTERVAL}s"
    echo " Auth       : $( [ -n "$HMAC_SECRET" ] && echo HMAC || echo 'legacy API key' )"
    echo " Watch Procs: ${WATCH_PROCS}"
    echo " Access Logs: ${ACCESS_LOGS:-<none>}"
    echo "============================================"

    while true; do
        drain_queue "$REPORT_URL"

        local host_json apps_json reqs_json certs_json slow_json
        host_json=$(collect_host)
        apps_json=$(collect_apps)
        reqs_json=$(collect_requests)
        certs_json=$(collect_certs)
        slow_json=$(collect_slow_queries)

        local payload
        payload=$(build_payload "$AGENT_URL" "$host_json" "$apps_json" "$reqs_json" "$certs_json" "$slow_json")
        send_payload "$REPORT_URL" "$payload" || enqueue_payload "$payload"

        sleep "$INTERVAL"
    done
}

# ============================================
# 수집 함수 (기존과 동일)
# ============================================
collect_host() {
    local hostname=$(hostname)
    local os_name=$(uname -s)
    local kernel=$(uname -r)
    local cpu_cores=$(nproc)

    read -r load1 load5 load15 _ < /proc/loadavg

    local cpu_used_pct
    local cpu_line1=$(head -1 /proc/stat)
    sleep 0.2
    local cpu_line2=$(head -1 /proc/stat)
    local idle1=$(echo "$cpu_line1" | awk '{print $5}')
    local total1=$(echo "$cpu_line1" | awk '{s=0; for(i=2;i<=NF;i++) s+=$i; print s}')
    local idle2=$(echo "$cpu_line2" | awk '{print $5}')
    local total2=$(echo "$cpu_line2" | awk '{s=0; for(i=2;i<=NF;i++) s+=$i; print s}')
    local diff_idle=$((idle2 - idle1))
    local diff_total=$((total2 - total1))
    if [ "$diff_total" -gt 0 ]; then
        cpu_used_pct=$(awk "BEGIN {printf \"%.1f\", (1 - $diff_idle/$diff_total) * 100}")
    else
        cpu_used_pct="0.0"
    fi

    local mem_total=$(awk '/^MemTotal/    {print $2 * 1024}' /proc/meminfo)
    local mem_free=$(awk '/^MemFree/     {print $2 * 1024}' /proc/meminfo)
    local mem_available=$(awk '/^MemAvailable/ {print $2 * 1024}' /proc/meminfo)
    local mem_used=$((mem_total - mem_available))
    local swap_total=$(awk '/^SwapTotal/  {print $2 * 1024}' /proc/meminfo)
    local swap_used
    swap_used=$(awk '/^SwapTotal/ {t=$2} /^SwapFree/ {f=$2} END {print (t-f)*1024}' /proc/meminfo)

    local uptime_seconds=$(awk '{printf "%d", $1}' /proc/uptime)

    # ---- 네트워크 RX/TX (bytes/sec 델타) ----
    local net_rx_bytes=0 net_tx_bytes=0
    while IFS= read -r line; do
        local iface=$(echo "$line" | awk -F':' '{gsub(/ /,"",$1); print $1}')
        [ "$iface" = "lo" ] && continue
        [[ "$iface" =~ ^(docker|br-|veth|virbr|tun|tap) ]] && continue
        local rx=$(echo "$line" | awk -F':' '{print $2}' | awk '{print $1}')
        local tx=$(echo "$line" | awk -F':' '{print $2}' | awk '{print $9}')
        [[ "$rx" =~ ^[0-9]+$ ]] && net_rx_bytes=$((net_rx_bytes + rx))
        [[ "$tx" =~ ^[0-9]+$ ]] && net_tx_bytes=$((net_tx_bytes + tx))
    done < <(tail -n +3 /proc/net/dev 2>/dev/null)

    local net_rx_bps=0 net_tx_bps=0
    local net_state_file="$STATE_DIR/net.prev"
    local now_ns=$(date +%s.%N)
    if [ -f "$net_state_file" ]; then
        local prev_rx prev_tx prev_ts
        read -r prev_rx prev_tx prev_ts < "$net_state_file"
        local dt
        dt=$(awk "BEGIN {printf \"%.3f\", $now_ns - $prev_ts}")
        if awk "BEGIN {exit !($dt > 0.1)}"; then
            net_rx_bps=$(awk "BEGIN {printf \"%d\", ($net_rx_bytes - $prev_rx) / $dt}")
            net_tx_bps=$(awk "BEGIN {printf \"%d\", ($net_tx_bytes - $prev_tx) / $dt}")
            [ "$net_rx_bps" -lt 0 ] && net_rx_bps=0
            [ "$net_tx_bps" -lt 0 ] && net_tx_bps=0
        fi
    fi
    echo "$net_rx_bytes $net_tx_bytes $now_ns" > "$net_state_file"

    # ---- TCP 연결 / LISTEN 포트 ----
    local tcp_established=0
    if command -v ss >/dev/null 2>&1; then
        tcp_established=$(ss -tnH state established 2>/dev/null | wc -l)
    elif [ -r /proc/net/tcp ]; then
        tcp_established=$(awk 'NR>1 && $4=="01"' /proc/net/tcp 2>/dev/null | wc -l)
    fi
    [[ ! "$tcp_established" =~ ^[0-9]+$ ]] && tcp_established=0

    local ports_json="["
    local pfirst=true
    if command -v ss >/dev/null 2>&1; then
        while IFS= read -r pline; do
            local addr=$(echo "$pline" | awk '{print $4}')
            local port="${addr##*:}"
            [[ ! "$port" =~ ^[0-9]+$ ]] && continue
            local proc_info=$(echo "$pline" | grep -oE 'users:\(\("[^"]+"[^)]+\)' | head -1 | sed -E 's/users:\(\("([^"]+)".*pid=([0-9]+).*/\1|\2/')
            local pname="${proc_info%%|*}"
            local ppid="${proc_info##*|}"
            [ "$pname" = "$proc_info" ] && pname=""
            [ "$ppid" = "$proc_info" ] && ppid=0
            [ "$pfirst" = true ] && pfirst=false || ports_json+=","
            local spn=$(echo "$pname" | sed 's/\\/\\\\/g; s/"/\\"/g')
            ports_json+="{\"port\":${port},\"proto\":\"tcp\",\"process\":\"${spn}\",\"pid\":${ppid:-0}}"
        done < <(ss -tlnHp 2>/dev/null | head -60)
    fi
    ports_json+="]"

    local disks_json="["
    local first=true
    while IFS= read -r line; do
        local fstype=$(echo "$line" | awk '{print $2}')
        local total_kb=$(echo "$line" | awk '{print $3}')
        local used_kb=$(echo "$line" | awk '{print $4}')
        local avail_kb=$(echo "$line" | awk '{print $5}')
        local mount=$(echo "$line" | awk '{print $7}')
        [[ ! "$total_kb" =~ ^[0-9]+$ ]] && continue
        [ "$first" = true ] && first=false || disks_json+=","
        disks_json+="{\"mount\":\"${mount}\",\"fsType\":\"${fstype}\","
        disks_json+="\"total\":$((total_kb * 1024)),\"used\":$((used_kb * 1024)),\"usable\":$((avail_kb * 1024))}"
    done < <(df -T -x tmpfs -x devtmpfs -x squashfs -x overlay 2>/dev/null | tail -n +2)
    disks_json+="]"

    cat <<HOSTEOF
{
  "hostname": "${hostname}",
  "osName": "${os_name}",
  "kernelVersion": "${kernel}",
  "cpuCores": ${cpu_cores},
  "loadAvg1": ${load1},
  "loadAvg5": ${load5},
  "loadAvg15": ${load15},
  "cpuUsedPct": ${cpu_used_pct},
  "memTotal": ${mem_total},
  "memUsed": ${mem_used},
  "memFree": ${mem_free},
  "memAvailable": ${mem_available},
  "swapTotal": ${swap_total},
  "swapUsed": ${swap_used},
  "uptimeSeconds": ${uptime_seconds},
  "netRxBps": ${net_rx_bps},
  "netTxBps": ${net_tx_bps},
  "tcpEstablished": ${tcp_established},
  "listenPorts": ${ports_json},
  "disks": ${disks_json}
}
HOSTEOF
}

mask_sensitive() {
    local input="$1"
    input=$(echo "$input" | sed -E \
        -e 's/([-][-]?(password|passwd|pass|pwd|secret|token|api[_-]?key|access[_-]?key|auth[_-]?token|private[_-]?key|client[_-]?secret)[= ]+)[^ ]*/\1*****/gi' \
        -e 's/(-[Pp]) +[^ ]+/\1 *****/g' \
        -e 's/(PASS(WORD)?|TOKEN|SECRET|API_KEY|ACCESS_KEY)=[^ ]*/\1=*****/gi')
    echo "$input"
}

collect_apps() {
    local procs_json="["
    local first=true

    IFS=',' read -ra PROC_LIST <<< "$WATCH_PROCS"
    for proc_name in "${PROC_LIST[@]}"; do
        proc_name=$(echo "$proc_name" | xargs)
        while IFS= read -r pid; do
            [ -z "$pid" ] && continue
            local raw_cmdline=$(tr '\0' ' ' < /proc/$pid/cmdline 2>/dev/null | head -c 200)
            local cmdline=$(mask_sensitive "$raw_cmdline")
            local mem_rss=$(awk '/^VmRSS/ {print $2 * 1024}' /proc/$pid/status 2>/dev/null)
            [ -z "$mem_rss" ] && mem_rss=0

            local start_time=$(stat -c %Y /proc/$pid 2>/dev/null)
            local now=$(date +%s)
            local proc_uptime=0
            [ -n "$start_time" ] && proc_uptime=$((now - start_time))

            [ "$first" = true ] && first=false || procs_json+=","
            local safe_cmdline=$(echo "$cmdline" | sed 's/\\/\\\\/g; s/"/\\"/g')
            procs_json+="{\"name\":\"${proc_name}\",\"pid\":${pid},\"registered\":true,"
            procs_json+="\"cmdline\":\"${safe_cmdline}\",\"memRss\":${mem_rss},\"uptimeSeconds\":${proc_uptime}}"
        done < <(pgrep -f "$proc_name" 2>/dev/null)
    done

    procs_json+="]"
    echo "$procs_json"
}

collect_requests() {
    if [ -z "$ACCESS_LOGS" ]; then
        echo "[]"
        return
    fi

    local out="["
    local first=true

    IFS=',' read -ra LOGS <<< "$ACCESS_LOGS"
    for logfile in "${LOGS[@]}"; do
        logfile=$(echo "$logfile" | xargs)
        [ -z "$logfile" ] && continue
        [ ! -r "$logfile" ] && continue

        local offset_key
        offset_key=$(echo "$logfile" | sed 's|[/ ]|_|g')
        local offset_file="$OFFSET_DIR/${offset_key}.offset"
        local current_size
        current_size=$(stat -c %s "$logfile" 2>/dev/null || echo 0)
        local prev_offset=0
        [ -f "$offset_file" ] && prev_offset=$(cat "$offset_file" 2>/dev/null || echo 0)

        if [ "$prev_offset" -gt "$current_size" ] || [ "$prev_offset" -eq 0 ]; then
            echo "$current_size" > "$offset_file"
            continue
        fi

        if [ "$prev_offset" -lt "$current_size" ]; then
            local parsed
            parsed=$(tail -c +$((prev_offset + 1)) "$logfile" 2>/dev/null \
                | head -c 500000 \
                | awk -v src="$logfile" -v now_ms="$(date +%s%3N)" -v ignore_re="$IGNORE_PATH_REGEX" '
                function json_escape(s) {
                    gsub(/\\/, "\\\\", s)
                    gsub(/"/, "\\\"", s)
                    return s
                }
                function trim(s,  t) {
                    t = s
                    sub(/^[ \t]+/, "", t)
                    sub(/[ \t]+$/, "", t)
                    return t
                }
                NF < 9 { next }
                {
                    ip = $1
                    req = $6
                    sub(/^"/, "", req)
                    method = req
                    path = $7
                    status = $9 + 0
                    bytes = ($10 == "-" ? 0 : $10 + 0)

                    elapsed_ms = 0
                    for (i = NF; i >= 11; i--) {
                        if ($i ~ /^[0-9]+\.[0-9]+$/) {
                            elapsed_ms = int($i * 1000)
                            break
                        }
                    }

                    if (length(path) > 200) path = substr(path, 1, 200)
                    if (length(method) > 10) method = substr(method, 1, 10)

                    if (method !~ /^[A-Z]+$/) next
                    if (status < 100 || status > 599) next

                    if (method == "CONNECT" || method == "TRACE") next

                    if (path_only ~ /^[a-zA-Z0-9.-]+:[0-9]+$/) next

                    path_only = path
                    sub(/\?.*$/, "", path_only)
                    if (path_only ~ /\.(png|jpe?g|gif|svg|ico|webp|bmp|css|js|mjs|map|woff2?|ttf|otf|eot|mp4|mp3|webm|wav|ogg|pdf|txt)$/) next

                    if (ignore_re != "" && path_only ~ ignore_re) next

                    printf "{\"timestamp\":%d,\"method\":\"%s\",\"path\":\"%s\",\"status\":%d,\"elapsedMs\":%d,\"bytes\":%d,\"remoteIp\":\"%s\",\"source\":\"%s\"}\n", now_ms, method, json_escape(path), status, elapsed_ms, bytes, ip, src
                }
            ')

            if [ -n "$parsed" ]; then
                while IFS= read -r obj; do
                    [ -z "$obj" ] && continue
                    [ "$first" = true ] && first=false || out+=","
                    out+="$obj"
                done <<< "$parsed"
            fi
        fi

        echo "$current_size" > "$offset_file"
    done

    out+="]"
    echo "$out"
}

collect_certs() {
    [ -z "$CERT_PATHS" ] && { echo "[]"; return; }

    local cache_file="$STATE_DIR/certs.cache.json"
    local ts_file="$STATE_DIR/certs.ts"
    local now=$(date +%s)
    local last=0
    [ -f "$ts_file" ] && last=$(cat "$ts_file" 2>/dev/null || echo 0)

    if [ -f "$cache_file" ] && [ $((now - last)) -lt $CERT_CHECK_INTERVAL ]; then
        cat "$cache_file"
        return
    fi

    local paths=()
    if [ "$CERT_PATHS" = "auto" ]; then
        shopt -s nullglob
        for cert in /etc/letsencrypt/live/*/fullchain.pem; do
            [ -f "$cert" ] && paths+=("$cert")
        done
        shopt -u nullglob
    else
        IFS=',' read -ra paths <<< "$CERT_PATHS"
    fi

    local out="["
    local first=true
    for cert_path in "${paths[@]}"; do
        cert_path=$(echo "$cert_path" | xargs)
        [ ! -r "$cert_path" ] && continue

        local end_line
        end_line=$(openssl x509 -noout -enddate -in "$cert_path" 2>/dev/null | sed 's/notAfter=//')
        [ -z "$end_line" ] && continue
        local not_after_epoch
        not_after_epoch=$(date -d "$end_line" +%s 2>/dev/null || echo 0)
        [ "$not_after_epoch" -eq 0 ] && continue

        local days_left=$(( (not_after_epoch - now) / 86400 ))

        local subject issuer
        subject=$(openssl x509 -noout -subject -in "$cert_path" 2>/dev/null \
            | sed -n 's/.*CN *= *\([^,\/]*\).*/\1/p' | head -1 | xargs)
        issuer=$(openssl x509 -noout -issuer -in "$cert_path" 2>/dev/null \
            | sed -n 's/.*CN *= *\([^,\/]*\).*/\1/p' | head -1 | xargs)

        local sans
        sans=$(openssl x509 -noout -ext subjectAltName -in "$cert_path" 2>/dev/null \
            | tr ',' '\n' | grep -oE 'DNS:[^ ]+' | sed 's/DNS://' | paste -sd, -)

        [ -z "$subject" ] && subject=$(basename "$(dirname "$cert_path")")

        [ "$first" = true ] && first=false || out+=","
        local sj=$(echo "$subject" | sed 's/\\/\\\\/g; s/"/\\"/g')
        local si=$(echo "$issuer" | sed 's/\\/\\\\/g; s/"/\\"/g')
        local ss=$(echo "$sans" | sed 's/\\/\\\\/g; s/"/\\"/g')
        local sp=$(echo "$cert_path" | sed 's/\\/\\\\/g; s/"/\\"/g')
        out+="{\"subject\":\"${sj}\",\"issuer\":\"${si}\",\"notAfter\":${not_after_epoch},\"daysLeft\":${days_left},\"sans\":\"${ss}\",\"source\":\"${sp}\"}"
    done
    out+="]"

    echo "$out" > "$cache_file"
    echo "$now" > "$ts_file"
    echo "$out"
}

collect_slow_queries() {
    [ -z "$SLOW_QUERY_LOG" ] && { echo "[]"; return; }
    [ ! -r "$SLOW_QUERY_LOG" ] && { echo "[]"; return; }

    local offset_file="$OFFSET_DIR/slowlog.offset"
    local current_size
    current_size=$(stat -c %s "$SLOW_QUERY_LOG" 2>/dev/null || echo 0)
    local prev_offset=0
    [ -f "$offset_file" ] && prev_offset=$(cat "$offset_file" 2>/dev/null || echo 0)

    if [ "$prev_offset" -gt "$current_size" ] || [ "$prev_offset" -eq 0 ]; then
        echo "$current_size" > "$offset_file"
        echo "[]"
        return
    fi

    if [ "$prev_offset" -ge "$current_size" ]; then
        echo "[]"
        return
    fi

    local chunk
    chunk=$(tail -c +$((prev_offset + 1)) "$SLOW_QUERY_LOG" 2>/dev/null | head -c 1000000)

    local parsed
    parsed=$(printf '%s\n' "$chunk" | awk -v src="$SLOW_QUERY_LOG" -v now_ms="$(date +%s%3N)" -v maxn="$SLOW_QUERY_MAX_PER_CYCLE" '
        function json_escape(s) {
            gsub(/\\/, "\\\\", s)
            gsub(/"/, "\\\"", s)
            gsub(/\r/, "", s)
            gsub(/\t/, " ", s)
            return s
        }
        function flush(   ts_ms, elapsed_ms, lock_ms, body) {
            if (!have_entry) return
            if (q_time == "") { reset(); return }
            body = sql
            sub(/^[ \t\n]+/, "", body)
            sub(/[ \t\n;]+$/, "", body)
            if (body == "" || body ~ /^#/) { reset(); return }
            if (tolower(body) ~ /^(administrator command|binlog dump)/) { reset(); return }
            if (length(body) > 8000) body = substr(body, 1, 8000)
            if (ts > 0) ts_ms = ts * 1000; else ts_ms = now_ms
            elapsed_ms = int(q_time * 1000 + 0.5)
            lock_ms = int(lk_time * 1000 + 0.5)
            printf "{\"timestamp\":%d,\"elapsedMs\":%d,\"lockMs\":%d,\"rowsSent\":%d,\"rowsExamined\":%d,\"database\":\"%s\",\"user\":\"%s\",\"clientHost\":\"%s\",\"sql\":\"%s\",\"source\":\"%s\"}\n", ts_ms, elapsed_ms, lock_ms, rs+0, re+0, json_escape(sch), json_escape(usr), json_escape(chost), json_escape(body), src
            emitted++
            reset()
        }
        function reset() {
            have_entry = 0; sql = ""; q_time = ""; lk_time = 0; rs = 0; re = 0; sch = ""; usr = ""; chost = ""; ts = 0
        }
        BEGIN { emitted = 0; reset() }
        emitted >= maxn+0 { exit }
        /^# Time:/ { flush(); have_entry = 1; next }
        /^# User@Host:/ {
            line = $0
            sub(/^# User@Host:[ \t]*/, "", line)
            if (match(line, /^[^\[ \t]+/)) {
                usr = substr(line, RSTART, RLENGTH)
            }
            if (match(line, /@[ \t]*[^ \t\[]+/)) {
                ch = substr(line, RSTART, RLENGTH)
                sub(/^@[ \t]*/, "", ch)
                chost = ch
            }
            next
        }
        /^# Thread_id:/ {
            if (match($0, /Schema:[ \t]*[^ \t]+/)) {
                s = substr($0, RSTART, RLENGTH)
                sub(/Schema:[ \t]*/, "", s)
                sch = s
            }
            next
        }
        /^# Query_time:/ {
            if (match($0, /Query_time:[ \t]*[0-9.]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/Query_time:[ \t]*/, "", s); q_time = s
            }
            if (match($0, /Lock_time:[ \t]*[0-9.]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/Lock_time:[ \t]*/, "", s); lk_time = s
            }
            if (match($0, /Rows_sent:[ \t]*[0-9]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/Rows_sent:[ \t]*/, "", s); rs = s
            }
            if (match($0, /Rows_examined:[ \t]*[0-9]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/Rows_examined:[ \t]*/, "", s); re = s
            }
            next
        }
        /^# / { next }
        /^SET timestamp=/ {
            if (match($0, /timestamp=[0-9]+/)) {
                s = substr($0, RSTART, RLENGTH); sub(/timestamp=/, "", s); ts = s + 0
            }
            next
        }
        /^[Uu][Ss][Ee][ \t]/ {
            line = $0
            sub(/^[Uu][Ss][Ee][ \t]+/, "", line)
            sub(/[;\t ]+$/, "", line)
            if (sch == "") sch = line
            next
        }
        {
            if (have_entry) {
                if (sql == "") sql = $0; else sql = sql " " $0
            }
        }
        END { flush() }
    ')

    echo "$current_size" > "$offset_file"

    local out="["
    local first=true
    if [ -n "$parsed" ]; then
        while IFS= read -r obj; do
            [ -z "$obj" ] && continue
            [ "$first" = true ] && first=false || out+=","
            out+="$obj"
        done <<< "$parsed"
    fi
    out+="]"
    echo "$out"
}

# ============================================
# Payload build / send / queue
# ============================================
build_payload() {
    local agent_url="$1"
    local host_json="$2"
    local apps_json="$3"
    local reqs_json="${4:-[]}"
    local certs_json="${5:-[]}"
    local slow_json="${6:-[]}"

    cat <<PAYLOADEOF
{
  "hostId": "${HOST_ID}",
  "displayName": "${DISPLAY_NAME}",
  "agentUrl": "${agent_url}",
  "host": ${host_json},
  "apps": ${apps_json},
  "requests": ${reqs_json},
  "certs": ${certs_json},
  "slowQueries": ${slow_json}
}
PAYLOADEOF
}

# HMAC-SHA256 over: agentId\ntimestamp\nbody  (with secret).  Returns lowercase hex.
hmac_hex() {
    local secret="$1"
    # payload read from stdin
    openssl dgst -sha256 -hmac "$secret" 2>/dev/null \
        | sed -E 's/^.*= *//' \
        | tr -d '[:space:]'
}

# send_payload REPORT_URL PAYLOAD
# returns 0 on HTTP 200, non-zero otherwise
send_payload() {
    local report_url="$1"
    local payload="$2"
    local headers=(-H "Content-Type: application/json")

    if [ -n "$HMAC_SECRET" ]; then
        local ts
        ts=$(date +%s)
        local sig
        sig=$(printf '%s\n%s\n%s' "$HOST_ID" "$ts" "$payload" | hmac_hex "$HMAC_SECRET")
        headers+=(-H "X-Agent-Id: ${HOST_ID}" -H "X-Timestamp: ${ts}" -H "X-Signature: ${sig}")
    elif [ -n "$API_KEY" ]; then
        headers+=(-H "X-API-Key: ${API_KEY}")
    fi

    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${report_url}" \
        "${headers[@]}" \
        --data-binary "${payload}" \
        --connect-timeout "$SEND_CONNECT_TIMEOUT" \
        --max-time "$SEND_MAX_TIME" 2>/dev/null)

    if [ "$http_code" = "200" ]; then
        echo "[$(date '+%H:%M:%S')] push OK -> ${report_url}"
        return 0
    else
        echo "[$(date '+%H:%M:%S')] push FAIL (HTTP ${http_code}) -> ${report_url}"
        return 1
    fi
}

enqueue_payload() {
    local payload="$1"
    mkdir -p "$QUEUE_DIR" 2>/dev/null || return 0

    # Evict oldest if over cap
    local count
    count=$(find "$QUEUE_DIR" -maxdepth 1 -type f -name '*.json' 2>/dev/null | wc -l)
    if [ "$count" -ge "$QUEUE_MAX_FILES" ]; then
        local to_remove=$((count - QUEUE_MAX_FILES + 1))
        find "$QUEUE_DIR" -maxdepth 1 -type f -name '*.json' -printf '%T@ %p\n' 2>/dev/null \
            | sort -n | head -n "$to_remove" | awk '{$1=""; sub(/^ /,""); print}' \
            | while IFS= read -r f; do [ -n "$f" ] && rm -f "$f"; done
    fi

    local fname="$QUEUE_DIR/$(date +%s%N)-$$.json"
    printf '%s' "$payload" > "$fname" 2>/dev/null
}

drain_queue() {
    local report_url="$1"
    [ -d "$QUEUE_DIR" ] || return 0
    local files
    files=$(find "$QUEUE_DIR" -maxdepth 1 -type f -name '*.json' -printf '%T@ %p\n' 2>/dev/null \
            | sort -n | head -n "$QUEUE_DRAIN_PER_TICK" | awk '{$1=""; sub(/^ /,""); print}')
    [ -z "$files" ] && return 0

    while IFS= read -r f; do
        [ -z "$f" ] && continue
        [ -f "$f" ] || continue
        local payload
        payload=$(cat "$f" 2>/dev/null)
        [ -z "$payload" ] && { rm -f "$f"; continue; }
        if send_payload "$report_url" "$payload"; then
            rm -f "$f"
        else
            # Stop draining this tick — central still unhealthy
            break
        fi
    done <<< "$files"
}

# ============================================
# 진입점
# ============================================
CMD="${1:-run}"

case "$CMD" in
    install)  cmd_install ;;
    run)      cmd_run ;;
    -h|--help|help)
        sed -n '3,20p' "$0"
        ;;
    *)
        echo "Usage: $0 {install|run}"
        exit 1
        ;;
esac
