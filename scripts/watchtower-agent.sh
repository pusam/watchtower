#!/bin/bash

# ============================================
# Watchtower Agent
# ============================================
# 사용법:
#   1) 아래 [설정] 섹션의 값을 이 서버에 맞게 수정
#   2) 설치:  sudo ./watchtower-agent.sh install
#   3) 확인:  sudo systemctl status watchtower-agent
#
#   수동 실행 (디버깅):  ./watchtower-agent.sh run
# ============================================

# =========== [설정] 서버마다 수정 ===========
# 중앙 서버 주소 (HTTPS 권장). 예: https://watchtower.example.com
WATCHTOWER_URL="https://<YOUR_CENTRAL_URL>"
HOST_ID="server-a"                                # 호스트 식별자 (영숫자/._- , 유일)
DISPLAY_NAME="Server A"                           # 대시보드 표시 이름
# 중앙 서버의 WATCHTOWER_AGENT_API_KEY 환경변수와 동일한 값으로 설정.
# 생성 예:  openssl rand -hex 32
API_KEY="<YOUR_AGENT_API_KEY>"
WATCH_PROCS="java,python,node"                    # 감시할 프로세스 (콤마 구분)
INTERVAL=5                                        # 수집 주기(초)
AGENT_PORT=19090                                  # pull fallback 포트

# --- 액세스 로그 (XLog / API 호출 추적) ---
# 콤마 구분 경로 (nginx combined format 권장, request_time 포함 시 응답시간도 기록)
# 예: "/var/log/nginx/access.log,/var/log/apache2/access.log"
# 비워두면 XLog 수집 안 함.
ACCESS_LOGS="/var/log/nginx/access.log"

# 경로 제외 패턴 (awk 확장 정규식). 매칭되는 요청은 수집에서 제외.
# 기본: GitLab Runner 폴링, health check, metrics scrape, git 프로토콜 경로
IGNORE_PATH_REGEX="^/api/v4/jobs/request|^/api/v4/runners|^/-/metrics|^/health|^/healthz|^/ping|/info/refs|/git-upload-pack|/git-receive-pack"

# --- SSL 인증서 만료 추적 ---
# "auto": /etc/letsencrypt/live/*/fullchain.pem 자동 탐색
# "":     비활성
# 경로 목록 (콤마 구분): "/etc/ssl/certs/foo.pem,/etc/ssl/certs/bar.pem"
CERT_PATHS="auto"
CERT_CHECK_INTERVAL=300      # 검사 주기 (초). 기본 5분.

# --- MariaDB/MySQL 슬로우 쿼리 로그 ---
# 경로를 지정하면 슬로우 쿼리도 수집합니다. 비워두면 비활성.
# MariaDB 설정 예: [mysqld] slow_query_log=1 / long_query_time=0.5 /
#                  slow_query_log_file=/var/log/mysql/mariadb-slow.log
# 에이전트 실행 유저에게 로그 파일 읽기 권한이 필요합니다.
SLOW_QUERY_LOG=""
SLOW_QUERY_MAX_PER_CYCLE=100
# ============================================

INSTALL_DIR="/opt/watchtower-agent"
SERVICE_FILE="/etc/systemd/system/watchtower-agent.service"
STATE_DIR="/var/lib/watchtower-agent"
OFFSET_DIR="$STATE_DIR/offsets"

# ============================================
# Install 모드
# ============================================
cmd_install() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "install 은 root 권한이 필요합니다. sudo 로 실행하세요."
        exit 1
    fi

    local SELF_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

    echo "==> Installing watchtower-agent..."
    echo "    URL      : ${WATCHTOWER_URL}"
    echo "    HOST_ID  : ${HOST_ID}"
    echo "    DISPLAY  : ${DISPLAY_NAME}"
    echo "    PROCS    : ${WATCH_PROCS}"
    echo "    LOGS     : ${ACCESS_LOGS:-<none>}"
    echo ""

    mkdir -p "$INSTALL_DIR" "$STATE_DIR" "$OFFSET_DIR"
    cp "$SELF_PATH" "$INSTALL_DIR/watchtower-agent.sh"
    chmod +x "$INSTALL_DIR/watchtower-agent.sh"

    cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=Watchtower Metrics Agent
After=network.target

[Service]
Type=simple
ExecStart=${INSTALL_DIR}/watchtower-agent.sh run
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable watchtower-agent
    systemctl restart watchtower-agent

    echo "==> Done! Status:"
    systemctl status watchtower-agent --no-pager
}

# ============================================
# Run 모드 (수집 & Push 루프)
# ============================================
cmd_run() {
    mkdir -p "$STATE_DIR" "$OFFSET_DIR" 2>/dev/null

    local LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}')
    local AGENT_URL="http://${LOCAL_IP}:${AGENT_PORT}"
    local REPORT_URL="${WATCHTOWER_URL}/api/agent/report"

    echo "============================================"
    echo " Watchtower Agent"
    echo " Host ID    : ${HOST_ID}"
    echo " Display    : ${DISPLAY_NAME}"
    echo " Target     : ${REPORT_URL}"
    echo " Interval   : ${INTERVAL}s"
    echo " Watch Procs: ${WATCH_PROCS}"
    echo " Access Logs: ${ACCESS_LOGS:-<none>}"
    echo "============================================"

    while true; do
        local host_json
        local apps_json
        local reqs_json
        local certs_json
        local slow_json
        host_json=$(collect_host)
        apps_json=$(collect_apps)
        reqs_json=$(collect_requests)
        certs_json=$(collect_certs)
        slow_json=$(collect_slow_queries)
        send_report "$AGENT_URL" "$REPORT_URL" "$host_json" "$apps_json" "$reqs_json" "$certs_json" "$slow_json"
        sleep "$INTERVAL"
    done
}

# ============================================
# 수집 함수
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

    # df -T 출력: Filesystem Type 1K-blocks Used Available Use% Mounted-on
    local disks_json="["
    local first=true
    while IFS= read -r line; do
        local fstype=$(echo "$line" | awk '{print $2}')
        local total_kb=$(echo "$line" | awk '{print $3}')
        local used_kb=$(echo "$line" | awk '{print $4}')
        local avail_kb=$(echo "$line" | awk '{print $5}')
        local mount=$(echo "$line" | awk '{print $7}')
        # 숫자가 아니면 skip (헤더 잔여물/깨진 라인 방어)
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

                    # 프록시 스캔 / 비정상 메서드 제외 (CONNECT, TRACE, OPTIONS probe 등)
                    if (method == "CONNECT" || method == "TRACE") next

                    # host:port 형태 경로 (오픈 프록시 스캔) 제외
                    if (path_only ~ /^[a-zA-Z0-9.-]+:[0-9]+$/) next

                    # 정적 리소스 제외 (이미지/CSS/JS/폰트/미디어/소스맵)
                    path_only = path
                    sub(/\?.*$/, "", path_only)
                    if (path_only ~ /\.(png|jpe?g|gif|svg|ico|webp|bmp|css|js|mjs|map|woff2?|ttf|otf|eot|mp4|mp3|webm|wav|ogg|pdf|txt)$/) next

                    # 사용자 정의 제외 패턴
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

    # 캐시 유효 시 그대로 사용
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

send_report() {
    local agent_url="$1"
    local report_url="$2"
    local host_json="$3"
    local apps_json="$4"
    local reqs_json="${5:-[]}"
    local certs_json="${6:-[]}"
    local slow_json="${7:-[]}"

    local payload=$(cat <<PAYLOADEOF
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
)

    local curl_args=(-s -o /dev/null -w "%{http_code}" \
        -X POST "${report_url}" \
        -H "Content-Type: application/json" \
        -d "${payload}" \
        --connect-timeout 3 \
        --max-time 5)

    if [ -n "$API_KEY" ]; then
        curl_args+=(-H "X-API-Key: ${API_KEY}")
    fi

    local http_code
    http_code=$(curl "${curl_args[@]}" 2>/dev/null)

    if [ "$http_code" = "200" ]; then
        echo "[$(date '+%H:%M:%S')] push OK -> ${report_url}"
    else
        echo "[$(date '+%H:%M:%S')] push FAIL (HTTP ${http_code}) -> ${report_url}"
    fi
}

# ============================================
# 진입점
# ============================================
CMD="${1:-run}"

case "$CMD" in
    install)  cmd_install ;;
    run)      cmd_run ;;
    -h|--help|help)
        sed -n '3,14p' "$0"
        ;;
    *)
        echo "Usage: $0 {install|run}"
        exit 1
        ;;
esac
