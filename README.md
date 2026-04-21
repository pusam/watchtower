# Watchtower

자체 호스팅 서버 모니터링 대시보드. Spring Boot + Thymeleaf + WebSocket + Bash 에이전트.

호스트 CPU/메모리/디스크/네트워크, 프로세스, nginx 액세스 로그(XLog), SSL 인증서 만료,
MariaDB 슬로우 쿼리, 알람(Slack 통지)까지 한 화면에서.

---

## ⚠️ 최초 실행 전 필수 설정

기본값은 **절대 그대로 쓰지 마세요.** 공개된 저장소 기본값은 누구나 알고 있으니,
대시보드 로그인과 에이전트 API 키를 **반드시** 교체하고 시작하세요.

### 1) 시크릿 생성

```bash
# 에이전트 API 키 (32바이트 hex)
openssl rand -hex 32

# 대시보드 비밀번호 (20자)
openssl rand -base64 20
```

### 2) 중앙 서버 환경변수

`bootRun` 또는 systemd/도커에서 환경변수로 주입합니다.

```bash
export WATCHTOWER_DASHBOARD_USER="admin"
export WATCHTOWER_DASHBOARD_PASS="<위에서 생성한 비밀번호>"
export WATCHTOWER_AGENT_API_KEY="<위에서 생성한 API 키>"
export WATCHTOWER_ALLOWED_ORIGIN="https://your-dashboard.example.com"   # CORS 오리진
export WATCHTOWER_SLACK_WEBHOOK="https://hooks.slack.com/services/..."  # 선택
```

### 3) 에이전트 스크립트 상단 4개 변수 교체

```bash
WATCHTOWER_URL="https://your-dashboard.example.com"
HOST_ID="server-a"
DISPLAY_NAME="Server A"
API_KEY="<위에서 생성한 API 키 — 중앙 서버와 동일해야 함>"
```

---

## 빠른 시작

### 1) 중앙 서버

```bash
./gradlew bootRun
```

기본 포트 `9090`. 브라우저: `http://<서버IP>:9090` → 로그인.

### 2) 대상 서버에 에이전트 설치

감시하려는 각 서버에서 `scripts/watchtower-agent.sh` 를 다운로드/복사하고,
상단 `[설정]` 섹션을 해당 서버에 맞게 수정한 뒤 설치:

```bash
sudo ./watchtower-agent.sh install
```

설치 후 systemd 서비스로 등록되어 재부팅에도 자동 실행됩니다.

---

## 에이전트 설치 가이드

### 필수 수정 항목 (파일 상단)

```bash
WATCHTOWER_URL="https://your-central.example.com"  # 중앙 서버 주소 (HTTPS 권장)
HOST_ID="server-a"                                  # 호스트 식별자 (영숫자/._- , 유일)
DISPLAY_NAME="Fulfillment Server A"                 # 대시보드 표시 이름
API_KEY="<YOUR_AGENT_API_KEY>"                      # 중앙 서버의 WATCHTOWER_AGENT_API_KEY 와 동일
WATCH_PROCS="java,python,node"                      # 감시할 프로세스 이름 (콤마 구분)
INTERVAL=5                                          # 수집 주기(초)
```

### 선택 기능 (필요한 것만 설정)

| 기능 | 변수 | 비고 |
|------|------|------|
| XLog (API 요청) | `ACCESS_LOGS` | nginx/apache 액세스 로그 경로 콤마 구분. `$request_time` 포함 포맷 권장 |
| 경로 제외 | `IGNORE_PATH_REGEX` | awk 확장 정규식. 헬스체크 등 제외 |
| SSL 인증서 | `CERT_PATHS` | `auto` = Let's Encrypt 자동 탐색, 또는 경로 목록 |
| MariaDB 슬로우쿼리 | `SLOW_QUERY_LOG` | 슬로우 로그 파일 경로 |

### 설치 & 확인

```bash
sudo ./watchtower-agent.sh install
sudo systemctl status watchtower-agent
journalctl -u watchtower-agent -f
```

### 제거

```bash
sudo systemctl disable --now watchtower-agent
sudo rm /etc/systemd/system/watchtower-agent.service
sudo rm -rf /opt/watchtower-agent /var/lib/watchtower-agent
sudo systemctl daemon-reload
```

---

## 호스트 등록 방식

**사전 등록 불필요.** 에이전트가 첫 push를 보내면 중앙 서버가 자동 등록합니다.
대시보드 사이드바에 호스트가 나타나면 성공.

- `HOST_ID` 는 서버마다 유일해야 하며 영숫자/`._-` 만 허용됩니다.
- `DISPLAY_NAME` 은 에이전트가 push할 때마다 갱신됩니다.
- `application.yml` 의 `watchtower.hosts` 항목은 풀(pull) 모드 백업용. 에이전트 기반(push)만 쓸 거면 비워둬도 됩니다.

---

## nginx 설정 (XLog 응답시간 포함)

기본 `combined` 포맷에는 `$request_time` 가 없어 모든 응답시간이 **0ms** 로 찍힙니다.
`/etc/nginx/nginx.conf` 의 `http { }` 블록 안 Logging Settings 에:

```nginx
log_format watchtower '$remote_addr - $remote_user [$time_local] '
                      '"$request" $status $body_bytes_sent '
                      '"$http_referer" "$http_user_agent" $request_time';
access_log /var/log/nginx/access.log watchtower;
```

```bash
sudo nginx -t && sudo systemctl reload nginx
```

`sites-available/*.conf` 같은 곳에 `access_log` 를 또 지정했다면 그곳도 `watchtower` 포맷으로 업데이트하거나 해당 라인을 지워 http 레벨을 상속받게 하세요.

---

## MariaDB 슬로우 쿼리 수집

### 1) MariaDB 설정

`/etc/mysql/mariadb.conf.d/50-server.cnf` 의 `[mysqld]` 블록:

```ini
slow_query_log = 1
slow_query_log_file = /var/log/mysql/mariadb-slow.log
long_query_time = 0.5
log_slow_verbosity = query_plan
# log_queries_not_using_indexes = 1   # 인덱스 안 탄 쿼리도 기록 (선택)
```

```bash
sudo systemctl restart mariadb
```

### 2) 에이전트가 읽을 수 있게 권한

```bash
sudo chmod 644 /var/log/mysql/mariadb-slow.log
# 또는 에이전트를 root 로 실행 (기본값)
```

### 3) 에이전트 설정

```bash
SLOW_QUERY_LOG="/var/log/mysql/mariadb-slow.log"
```

→ `sudo systemctl restart watchtower-agent`

대시보드 "느린 쿼리" 카드에 SQL 패턴별(리터럴/숫자 정규화) 집계가 나옵니다.
행 클릭 시 샘플 SQL + 통계 상세.

---

## 알람 (Slack 통지)

### 서버 환경변수

```bash
export WATCHTOWER_SLACK_WEBHOOK="https://hooks.slack.com/services/..."
```

또는 `application.yml` 의 `watchtower.alarms.slack-webhook-url` 에 직접.

### 기본 임계치 (application.yml)

| 타입 | 기본값 |
|------|--------|
| CPU | 90% |
| 메모리 | 90% |
| 디스크 | 85% |
| 5xx 에러율 | 5% (최근 60초) |
| 응답지연 | 3000ms |
| 인증서 만료 | 14일 |
| 호스트 다운 | 30초 무응답 |

쿨다운 기본 300초 (같은 알람 재발송 방지).

---

## 보안

| 항목 | 환경변수 |
|------|---------|
| 대시보드 로그인 | `WATCHTOWER_DASHBOARD_USER`, `WATCHTOWER_DASHBOARD_PASS` |
| 에이전트 → 서버 인증 | `WATCHTOWER_AGENT_API_KEY` (에이전트 `API_KEY` 와 일치) |
| CORS 허용 오리진 | `WATCHTOWER_ALLOWED_ORIGIN` |

운영 환경에서는 반드시 **기본값 변경 + HTTPS(리버스 프록시/ngrok)** 적용.

---

## 수집 항목 요약

- **호스트 메트릭**: CPU/Load, 메모리/Swap, 디스크(per mount), 네트워크 RX/TX bps, 업타임, TCP ESTABLISHED 수, LISTEN 포트 목록
- **프로세스**: `WATCH_PROCS` 에 지정한 이름으로 매칭되는 PID들의 RSS, cmdline, 업타임 (비밀번호 마스킹)
- **XLog**: 최근 API 요청 (method/path/status/elapsed/remoteIp), 스캐터 차트
- **엔드포인트 집계**: 경로 패턴 정규화(`/{id}`/`/{uuid}`), 건수/avg/p50/p95/p99/max, 에러율
- **상태코드 분포**: 15초 버킷 스택 차트 (2xx/3xx/4xx/5xx)
- **인증서**: CN/SAN/만료일 (Let's Encrypt auto 탐색 또는 수동 경로)
- **슬로우 쿼리**: MariaDB 슬로우 로그 기반 SQL 패턴 집계
- **알람**: 실시간 스트립 + 이력, Slack 통지

---

## 운영 배포 (HTTPS / 리버스 프록시)

Watchtower는 기본 포트 9090으로 HTTP 만 리스닝한다. 운영에서는 nginx 또는 Caddy 같은 리버스 프록시 뒤에 두는 것을 권장한다.

### nginx 예시

```nginx
upstream watchtower {
    server 127.0.0.1:9090;
}

server {
    listen 443 ssl http2;
    server_name watchtower.example.com;

    ssl_certificate     /etc/letsencrypt/live/watchtower.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/watchtower.example.com/privkey.pem;

    # WebSocket (SockJS/STOMP) 지원
    location /ws {
        proxy_pass http://watchtower;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        proxy_pass http://watchtower;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name watchtower.example.com;
    return 301 https://$host$request_uri;
}
```

### Caddy 예시 (자동 TLS)

```caddy
watchtower.example.com {
    reverse_proxy 127.0.0.1:9090
}
```

### application.yml 권장 설정

리버스 프록시 뒤에서는 다음을 `application.yml` 또는 환경변수로 설정한다:

```yaml
server:
  forward-headers-strategy: native
watchtower:
  security:
    allowed-origins:
      - https://watchtower.example.com
```

### 에이전트 엔드포인트

에이전트(`watchtower-agent.sh`)의 `WATCHTOWER_URL`도 HTTPS URL로 변경해야 한다. 자체 서명 인증서를 쓰는 경우 `curl --cacert` 옵션 또는 신뢰된 CA를 사용할 것.

### 방화벽

- 443 (또는 80): 리버스 프록시 외부 노출
- 9090: `localhost`에서만 접근 (외부 노출 금지)

---

## 사용자 / 권한

`watchtower.security.users`에 여러 사용자와 역할(ADMIN / OPERATOR / VIEWER)을 설정할 수 있다:

```yaml
watchtower:
  security:
    dashboard-username: admin
    dashboard-password: ${WATCHTOWER_DASHBOARD_PASS}   # 반드시 환경변수로 주입 (미설정 시 기동 실패)
    users:
      - username: ops
        password: opspass
        role: OPERATOR
      - username: viewer
        password: viewpass
        role: VIEWER
```

| 역할 | 권한 |
|------|------|
| ADMIN | 모든 API (POST/PUT/DELETE 포함) |
| OPERATOR | 조회 + 알람 ack + 유지보수 뮤트 |
| VIEWER | 조회 전용 |

---

## 알림 채널

동시에 여러 웹훅을 설정할 수 있다:

```yaml
watchtower:
  alarms:
    slack-webhook-url: https://hooks.slack.com/services/...
    discord-webhook-url: https://discord.com/api/webhooks/...
    generic-webhook-url: https://example.com/alert    # JSON POST
```

`generic-webhook-url`은 다음 형식의 JSON을 POST한다:

```json
{
  "id": "uuid",
  "hostId": "host-1",
  "hostName": "web-01",
  "type": "CPU",
  "severity": "WARN",
  "state": "FIRING",
  "message": "CPU 사용률 92.3%",
  "value": 92.3,
  "threshold": 90.0,
  "firedAt": 1712345678901,
  "resolvedAt": null,
  "acknowledged": false,
  "acknowledgedBy": null
}
```

---

## 유지보수 창 (Maintenance Window)

배포/점검 중 알림을 끄려면:

1. **일시 뮤트 (런타임)** - 헤더의 `알림 뮤트` 버튼 또는 `POST /api/maintenance/mute`
   ```bash
   curl -u admin:$WATCHTOWER_DASHBOARD_PASS -X POST http://localhost:9090/api/maintenance/mute \
        -H 'Content-Type: application/json' \
        -d '{"durationSec": 3600}'         # 1시간 전체 뮤트
   # 특정 호스트만
   curl -u admin:$WATCHTOWER_DASHBOARD_PASS -X POST http://localhost:9090/api/maintenance/mute \
        -H 'Content-Type: application/json' \
        -d '{"hostId": "web-01", "durationSec": 1800}'
   # 해제
   curl -u admin:$WATCHTOWER_DASHBOARD_PASS -X POST http://localhost:9090/api/maintenance/unmute
   ```
2. **스케줄 기반 (config)**
   ```yaml
   watchtower:
     maintenance:
       - name: weekly-deploy
         from: 2026-04-25T02:00:00+09:00
         to:   2026-04-25T03:00:00+09:00
         hostIds: [ web-01, web-02 ]   # 비우면 전체
   ```

---

## 알람 ack (확인)

알람 카드의 `ack` 버튼 또는:

```bash
curl -u operator:pass -X POST http://localhost:9090/api/alarms/<alarm-id>/ack
```

ack된 알람은 조건이 지속되어도 추가 슬랙/디스코드 알림이 발송되지 않는다. 해결(RESOLVED) 시점에는 다시 알림이 간다.

---

## 문제 해결

| 증상 | 원인/해결 |
|------|-----------|
| 호스트가 대시보드에 안 보임 | 에이전트 로그(`journalctl -u watchtower-agent -f`). `WATCHTOWER_URL` 도달 가능한지 확인. `API_KEY` 불일치 시 401/403 |
| XLog 응답시간 모두 0ms | nginx `log_format` 에 `$request_time` 빠짐 → 위 nginx 섹션 참고 |
| 디스크/네트워크 수치가 이상함 | 최초 1회는 0 (델타 계산용). 두 번째 사이클부터 정상 |
| 슬로우 쿼리 카드 비어있음 | `SLOW_QUERY_LOG` 경로/권한, MariaDB `slow_query_log=ON`, `long_query_time` 값 확인. 실제로 느린 쿼리 한 번 실행해 보기 |
| UI 변경이 반영 안됨 | 중앙 서버 재기동 (정적 리소스는 classpath 캐시) + 브라우저 `Ctrl+Shift+R` |
| LISTEN 포트 목록 비어있음 | `ss` 명령 없거나 권한 부족. `iproute2` 패키지 설치, 에이전트 root 실행 권장 |

---

## 프로젝트 구조

```
src/main/java/com/watchtower/
  ├─ alarm/          AlarmEngine, SlackNotifier
  ├─ analytics/      EndpointStatsService, SlowQueryStatsService
  ├─ collector/      AgentReceiver (push 수신), AgentClient (pull fallback), MetricsCollector
  ├─ config/         MonitorProperties, SecurityConfig
  ├─ domain/         HostSnapshot, AlarmEvent, EndpointStat, SlowQueryStat
  ├─ registry/       HostRegistry
  ├─ store/          MetricsStore (in-memory)
  ├─ web/            DashboardController, MetricsApiController
  └─ websocket/      MetricsPublisher (/topic/metrics, /topic/alarms)

src/main/resources/
  ├─ application.yml
  ├─ static/{css,js}/dashboard.*
  └─ templates/dashboard.html

scripts/
  └─ watchtower-agent.sh   # 대상 서버에서 실행할 bash 에이전트
```
