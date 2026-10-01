#!/usr/bin/env bash
# ============================================================
# 跨进程换电联跑（CI 门禁；本地等价脚本 cross-process-swap.ps1）
#
# 两个真进程、真 MQTT 5、真签名：
#   进程 A  buddy.jar（内嵌 Vert.x MQTT Broker + 业务侧）
#   进程 B  buddy-sim.jar（柜机设备侧，门开后自动代用户投入/取走）
#   驱动    本脚本，只走公开 HTTP API（不改库、不用测试替身）
#
# 为什么值得单开一个 CI job：同 JVM 的云端测试里"设备"是 buddy 自己的客户端，
# 用自己的密钥与自己的理解生成报文，**两端同源**；本 job 已连续挖出 4 个
# 同 JVM 永远测不到的真缺陷（会话号权威来源、事件按订单未串行、TAKEN 后关门被丢、
# ACKED 指令被反复按超时处理）。
#
# 四维断言：状态 / 资产 / 权益 / 事件流，缺一不可（只看订单完成不算通过）。
#
# 为什么有 --selftest（重要，来自一次真实事故）：本机没有系统 bash，这份脚本曾被
# 我按"PowerShell 版跑通了"就推上 CI，结果 CI 连续三红——真实原因是 `api` 的调用
# 参数个数错了（多塞一个空串占位），令牌落到 $5 而函数读 $4，于是"登录成功、令牌
# 长度 280，下一个请求却带令牌=no 并 401"。这种错 bash -n 查不出来，只能真跑。
# --selftest 用假 curl 断言"令牌真的进了请求头 + 参数误用会硬失败"，不依赖后端，
# 所以本机（Git 自带 bash）与 CI 都能跑，两边跑的是同一份文件。
# ============================================================
set -euo pipefail

BASE="${BASE:-http://127.0.0.1:8200/api}"
PRODUCT="${PRODUCT:-SWAP-CAB-8}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-Admin@123456}"
POLL_SECONDS="${POLL_SECONDS:-90}"

# ---------------- 请求工具 ----------------

# 组装 curl 参数（纯函数，便于 selftest 断言）：签名固定为 METHOD PATH [JSON] [TOKEN]
build_args() {  # build_args METHOD PATH [JSON] [TOKEN] -> 打印参数，每行一个
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  if [ "$#" -gt 4 ]; then
    # 参数多一个就会让令牌静默消失（就是 CI 三红的原因），所以宁可现在炸
    echo "build_args 调用参数过多（$# 个；最多 4 个：METHOD PATH [JSON] [TOKEN]）" >&2
    return 2
  fi
  case "$method" in GET | POST | PUT | DELETE | PATCH) ;; *)
    echo "build_args：第 1 个参数必须是 HTTP 方法，实际是 '$method'（METHOD/PATH 顺序写反？）" >&2
    return 2 ;;
  esac
  printf '%s\n' -sS -X "$method" "$BASE$path" -H 'content-type: application/json; charset=utf-8'
  if [ -n "$token" ]; then printf '%s\n' -H "Authorization: Bearer $token"; fi
  if [ -n "$body" ]; then printf '%s\n' -d "$body"; fi
  printf '%s\n' -w $'\n%{http_code}'
}

api() {  # api METHOD PATH [JSON] [TOKEN] -> 成功时打印响应体；失败返回非 0 并打印可定位信息
  local method="$1" path="${2:-}" token="${4:-}" body="${3:-}"
  local args=() line
  while IFS= read -r line; do args+=("$line"); done < <(build_args "$@")
  if [ -n "$token" ] && [ "$token" != "null" ]; then :; fi
  if [ "$token" = "null" ]; then
    # jq 取不到字段时返回字面量 null：看着"有值"，其实没有令牌
    echo "!! api $method $path：token 是字面量 null（上一步没从响应里取到令牌）" >&2
  fi
  local out status
  if ! out="$(curl "${args[@]}")"; then
    echo "!! api $method $path：curl 失败（连不上或对端断连）" >&2
    return 1
  fi
  status="${out##*$'\n'}"
  if [ "$status" -ge 400 ] 2>/dev/null; then
    echo "!! api $method $path → HTTP $status，带令牌=$([ -n "$token" ] && echo yes || echo no)" >&2
    printf '%s\n' "${out%$'\n'*}" >&2
    return 1
  fi
  printf '%s' "${out%$'\n'*}"
}

need_code() {  # need_code JSON_RESPONSE —— code!=200 直接失败并打印 message
  local json="$1" code
  code="$(printf '%s' "$json" | jq -r '.code')"
  if [ "$code" != "200" ]; then
    echo "接口返回 code=$code message=$(printf '%s' "$json" | jq -r '.message')" >&2
    exit 1
  fi
}

# ---------------- 自检（不需要后端；CI 与本机都先跑这一步）----------------

selftest() {
  local fails=0
  # 1) 正常调用：令牌必须进请求头
  local args; args="$(build_args POST /x '{"a":1}' 'TOK-123' | tr '\n' '~')"
  case "$args" in
    *"-H~Authorization: Bearer TOK-123~"*) echo "  [PASS] 令牌进入请求头" ;;
    *) echo "  [FAIL] 令牌没进请求头：$args"; fails=$((fails + 1)) ;;
  esac
  # 2) 只给 METHOD PATH 时不该冒出 Authorization 头
  local bare; bare="$(build_args GET /x | tr '\n' '~')"
  case "$bare" in
    *Authorization*) echo "  [FAIL] 无令牌却带了 Authorization：$bare"; fails=$((fails + 1)) ;;
    *) echo "  [PASS] 无令牌时不带 Authorization" ;;
  esac
  # 3) 多塞一个占位空串（本次事故的写法）必须被硬拒，而不是静默丢令牌
  if build_args POST /x '{"a":1}' '' 'TOK' >/dev/null 2>&1; then
    echo "  [FAIL] 5 参数调用被接受了（正是 CI 三红的形状）"; fails=$((fails + 1))
  else
    echo "  [PASS] 参数个数超限时硬失败"
  fi
  # 4) METHOD/PATH 写反必须被发现
  if build_args '/swap/orders/X' GET '' 'TOK' >/dev/null 2>&1; then
    echo "  [FAIL] METHOD 与 PATH 写反却被接受了"; fails=$((fails + 1))
  else
    echo "  [PASS] METHOD/PATH 顺序写反时硬失败"
  fi
  # 5) 真实调用形态：body + 令牌同时存在时，令牌仍在
  local both; both="$(build_args POST /swap/devices '{"productKey":"P"}' 'ADM' | tr '\n' '~')"
  case "$both" in
    *"-d~{\"productKey\":\"P\"}~-w"*) echo "  [PASS] body 与令牌可共存" ;;
    *) echo "  [FAIL] body/令牌共存时参数顺序异常：$both"; fails=$((fails + 1)) ;;
  esac
  if [ "$fails" -ne 0 ]; then
    echo "selftest 失败 $fails 项：脚本的参数装配已经坏掉，不必再去 CI 上等 401" >&2
    return 1
  fi
  echo "selftest 全部通过"
}

if [ "${1:-}" = "--selftest" ]; then
  selftest
  exit $?
fi

# ---------------- 主流程 ----------------

SUFFIX="$(date +%s)$RANDOM"
DEVICE="CABO-XP-$SUFFIX"
CABINET="CAB-XP-$SUFFIX"
OFFER="BAT-XP-OFFER-$SUFFIX"
OLD="BAT-XP-OLD-$SUFFIX"
PHONE="139$(printf '%08d' $((RANDOM % 100000000)))"

echo "设备=$DEVICE 柜机=$CABINET 会员手机=$PHONE"

# 1) 后台身份 + 设备开通 + 建账 + 电池入仓（只一块满电电池，让取电仓唯一确定）
admin_login="$(api POST /auth/login "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")"
need_code "$admin_login"
admin="$(printf '%s' "$admin_login" | jq -r '.data.token // empty')"
if [ -z "$admin" ]; then
  echo "!! 登录响应里没有 data.token；顶层键=$(printf '%s' "$admin_login" | jq -r 'keys | join(",")')，data 键=$(printf '%s' "$admin_login" | jq -r '(.data // {}) | keys | join(",")')" >&2
  exit 1
fi
echo "后台登录 OK（令牌长度=${#admin}）"

credential="$(api POST /swap/devices "{\"productKey\":\"$PRODUCT\",\"deviceId\":\"$DEVICE\",\"deviceName\":\"跨进程联跑柜\"}" "$admin")"
need_code "$credential"
# 主密钥只在开通响应里出现一次；用错密钥会被 Broker 直接拒（NOT_AUTHORIZED）
SECRET="$(printf '%s' "$credential" | jq -r '.data.masterSecret // empty')"
[ -n "$SECRET" ] || { echo "开通接口未返回主密钥（data 键=$(printf '%s' "$credential" | jq -r '(.data // {}) | keys | join(",")')）" >&2; exit 1; }

need_code "$(api POST /swap/cabinets "{\"siteId\":1,\"productKey\":\"$PRODUCT\",\"cabinetNo\":\"$CABINET\",\"deviceId\":\"$DEVICE\",\"slotCount\":8}" "$admin")"
need_code "$(api POST "/swap/cabinets/$CABINET/batteries" "{\"batteryCode\":\"$OFFER\",\"productKey\":\"BAT-60V20AH\",\"slotNo\":1,\"soc\":98,\"temp\":27.0,\"capacityAh\":20.0,\"voltageV\":60.0}" "$admin")"
need_code "$(api POST "/swap/cabinets/$CABINET/batteries" "{\"batteryCode\":\"$OLD\",\"productKey\":\"BAT-60V20AH\",\"slotNo\":2,\"soc\":30,\"temp\":27.0,\"capacityAh\":20.0,\"voltageV\":60.0}" "$admin")"

# 2) C 端：注册即登录 → 发放额度 → 实名（MOCK 短信通道回显验证码）
code="$(api POST /member/auth/sms-code "{\"phone\":\"$PHONE\",\"purpose\":\"LOGIN\"}" | jq -r .data.echoCode)"
login="$(api POST /member/auth/login "{\"phone\":\"$PHONE\",\"code\":\"$code\",\"deviceType\":\"H5\"}")"
need_code "$login"
MTOK="$(printf '%s' "$login" | jq -r '.data.accessToken // empty')"
MEMBER_ID="$(printf '%s' "$login" | jq -r .data.memberId)"
[ -n "$MTOK" ] || { echo "C 端登录没拿到 accessToken" >&2; exit 1; }
need_code "$(api POST /swap/rights/grant "{\"memberId\":$MEMBER_ID,\"times\":5,\"validDays\":30,\"remark\":\"跨进程联跑发放\"}" "$admin")"
rcode="$(api POST /member/auth/sms-code "{\"phone\":\"$PHONE\",\"purpose\":\"REALNAME\"}" | jq -r .data.echoCode)"
need_code "$(api POST /member/me/realname "{\"realName\":\"联跑骑手\",\"idNo\":\"1101011990$(printf '%07d' $((RANDOM % 10000000)))X\",\"smsCode\":\"$rcode\"}" "$MTOK")"

# 3) 起设备侧进程
SIM_JAR="${SIM_JAR:-buddy-sim/target/buddy-sim.jar}"
java -jar "$SIM_JAR" --host 127.0.0.1 --port 1883 --product "$PRODUCT" --device "$DEVICE" \
  --secret "$SECRET" --slots 8 --offer-battery "$OFFER" --offer-slot 1 --old-battery "$OLD" \
  --swap-delay-ms 700 --run-seconds $((POLL_SECONDS + 30)) --auto-swap \
  > /tmp/xp-sim.log 2> /tmp/xp-sim-err.log &
SIM_PID=$!
trap 'kill $SIM_PID 2>/dev/null || true' EXIT

# 4) 等上线（没上线就建单会被 guard 拒，那是正确行为）
online=""
for _ in $(seq 1 15); do
  online="$(api GET "/swap/devices/$DEVICE?productKey=$PRODUCT" "" "$admin" | jq -r '.data.onlineState // "NONE"')"
  [ "$online" = "ONLINE" ] && break
  sleep 2
done
[ "$online" = "ONLINE" ] || { echo "设备未上线（$online）；柜侧日志："; tail -20 /tmp/xp-sim-err.log || true; exit 1; }
echo "设备已上线"

# 5) 建单 → 开仓 → 轮询终态
created="$(api POST /member/swap/orders "{\"cabinetNo\":\"$CABINET\"}" "$MTOK")"
need_code "$created"
if [ "$(printf '%s' "$created" | jq -r .data.accepted)" != "true" ]; then
  echo "建单被拒：$(printf '%s' "$created" | jq -r '.data.rejectReasons | join(",")')" >&2
  exit 1
fi
ORDER_NO="$(printf '%s' "$created" | jq -r .data.orderNo)"
echo "订单 $ORDER_NO 已创建"
need_code "$(api POST "/member/swap/orders/$ORDER_NO/start" "" "$MTOK")"

FINAL=""
for _ in $(seq 1 $((POLL_SECONDS / 2))); do
  sleep 2
  FINAL="$(api GET "/member/swap/orders/$ORDER_NO" "" "$MTOK" | jq -r '.data.displayState // "NONE"')"
  case "$FINAL" in SUCCESS | REJECTED | CANCELLED) break ;; esac
done
echo "最终展示态：$FINAL"
[ "$FINAL" = "SUCCESS" ] || { echo "跨进程联跑未跑成一单" >&2; tail -20 /tmp/xp-sim-err.log || true; exit 1; }

# 6) 四维断言：状态 / 资产 / 权益 / 事件流
detail="$(api GET "/swap/orders/$ORDER_NO" "" "$admin")"
[ "$(printf '%s' "$detail" | jq -r .data.orderState)" = "COMPLETED" ] || { echo "订单态不是 COMPLETED" >&2; exit 1; }
stuck="$(printf '%s' "$detail" | jq -r '[.data.steps[] | select(.step_state=="PENDING" or .step_state=="DISPATCHED")] | length')"
[ "$stuck" = "0" ] || { echo "仍有步骤在等待中：$stuck 个" >&2; exit 1; }
used="$(api GET "/swap/rights/$MEMBER_ID" "" "$admin" | jq -r .data.times_used)"
[ "$used" = "1" ] || { echo "权益实扣不正确：$used" >&2; exit 1; }
held="$(api GET "/swap/batteries?state=HELD_BY_USER&limit=200" "" "$admin" | jq -r --arg c "$OFFER" '[.data[] | select(.battery_code==$c)] | length')"
[ "$held" = "1" ] || { echo "新电池未变成用户持有" >&2; exit 1; }
back="$(api GET "/swap/batteries?limit=200" "" "$admin" | jq -r --arg c "$OLD" '[.data[] | select(.battery_code==$c and (.battery_state|startswith("IN_CABINET")))] | length')"
[ "$back" = "1" ] || { echo "旧电池未回到柜内池" >&2; exit 1; }
# 事件流可重放：每一跳的 from 等于上一跳的 to，末跳等于当前态
broken="$(printf '%s' "$detail" | jq -r '
  [ .data.events[] | select(.from_state != null and .to_state != null) ] as $e
  | [ range(0; ($e|length) - 1) | select($e[.].to_state != $e[. + 1].from_state) ] | length')"
[ "$broken" = "0" ] || { echo "事件流断裂：$broken 处" >&2; exit 1; }
last="$(printf '%s' "$detail" | jq -r '[ .data.events[] | select(.to_state != null) ][-1].to_state')"
[ "$last" = "COMPLETED" ] || { echo "事件流末态不是 COMPLETED：$last" >&2; exit 1; }

echo "联跑通过：权益实扣 1 次、新旧电池归属已互换、事件流可重放至 COMPLETED"
