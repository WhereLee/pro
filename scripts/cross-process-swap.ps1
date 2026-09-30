# ============================================================
# 跨进程联跑（Windows / 本地开发用；CI 侧等价脚本是 cross-process-swap.sh）
#
# 两个真进程、真 MQTT 5、真签名：
#   进程 A：buddy.jar（内嵌 Vert.x MQTT Broker + 业务侧）
#   进程 B：buddy-sim.jar（柜机设备侧，自动完成开门→投入→关门→开取电仓→取走）
#   驱动方：本脚本用 HTTP API 走 C 端会员链路（注册→发放额度→实名→建单→开仓→轮询）
#
# 这里刻意不用测试替身也不改库：只有"通过公开 API 能把一单跑到完成"，
# 才证明两端的协议实现真的对得上（这正是 golden 样本测不到的那一层）。
# ============================================================
param(
    [string]$Base = "http://127.0.0.1:8200/api",
    [string]$Product = "SWAP-CAB-8",
    [string]$DeviceSecret = "dev-only-iot-device-master-key-change-me",
    [string]$AdminUser = "admin",
    [string]$AdminPass = "Admin@123456",
    [int]$PollSeconds = 90
)
$ErrorActionPreference = "Stop"

function Invoke-Api($method, $path, $body, $token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    if ($null -ne $body) {
        $json = ($body | ConvertTo-Json -Compress -Depth 6)
        $resp = Invoke-RestMethod -Uri "$Base$path" -Method $method -Headers $headers `
                -ContentType "application/json; charset=utf-8" -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
    } else {
        $resp = Invoke-RestMethod -Uri "$Base$path" -Method $method -Headers $headers
    }
    if ($resp.PSObject.Properties.Name -contains "code" -and $resp.code -ne 200) {
        throw "API $path 返回 code=$($resp.code) message=$($resp.message)"
    }
    return $resp.data
}

# 后缀必须是纯字母数字：deviceId 的校验是 ^[A-Za-z0-9_-]{4,64}$，
# 而 `Get-Date -UFormat %s` 在本机会返回带小数的串（“1790807390.27”），一点就 400。
$suffix = "{0:x}{1:x}" -f [int](Get-Random -Maximum 999999), (Get-Random -Maximum 999)
$device = "CABO-XP-$suffix"
$cabinet = "CAB-XP-$suffix"
$offerBattery = "BAT-XP-OFFER-$suffix"
$oldBattery = "BAT-XP-OLD-$suffix"
$phone = "139{0:D8}" -f (Get-Random -Maximum 99999999)

Write-Host "设备=$device 柜机=$cabinet 会员手机=$phone"

# 1) 后台身份 + 设备开通 + 台账建账 + 电池入仓
$admin = (Invoke-Api POST "/auth/login" @{ username = $AdminUser; password = $AdminPass }).token
# 设备密钥只能用开通接口一次性拿到的那一个：参数里的 $DeviceSecret 是开发预置设备的密钥，
# 新开通的柜机用它会直接被 Broker 拒（NOT_AUTHORIZED）——这个“拿错密钥”的故忌故意写在注释里。
$credential = Invoke-Api POST "/swap/devices" @{ productKey = $Product; deviceId = $device; deviceName = "跨进程联跑柜" } $admin
$deviceSecret = $credential.masterSecret
if (-not $deviceSecret) { throw "开通接口没返回密钥（不应发生：明文只在创建时返回一次）" }
Invoke-Api POST "/swap/cabinets" @{
    siteId = 1; productKey = $Product; cabinetNo = $cabinet; deviceId = $device; slotCount = 8
} $admin | Out-Null
# 只注册一块满电电池：让"取电仓"的分配结果唯一确定，联跑才有可断言的落点
Invoke-Api POST "/swap/cabinets/$cabinet/batteries" @{
    batteryCode = $offerBattery; productKey = "BAT-60V20AH"; slotNo = 1; soc = 98; temp = 27.0; capacityAh = 20.0; voltageV = 60.0
} $admin | Out-Null
# 旧电池也必须在台账里：S3 核验要认得出被投入的是哪块电池（认不出就会中止，那是正确行为）
Invoke-Api POST "/swap/cabinets/$cabinet/batteries" @{
    batteryCode = $oldBattery; productKey = "BAT-60V20AH"; slotNo = 2; soc = 30; temp = 27.0; capacityAh = 20.0; voltageV = 60.0
} $admin | Out-Null

# 2) C 端注册即登录 + 发放额度 + 实名（MOCK 通道回显验证码）
$code = (Invoke-Api POST "/member/auth/sms-code" @{ phone = $phone; purpose = "LOGIN" }).echoCode
$login = Invoke-Api POST "/member/auth/login" @{ phone = $phone; code = $code; deviceType = "H5" }
$member = Invoke-Api POST "/member/auth/sms-code" @{ phone = $phone; purpose = "REALNAME" }
# 身份证号必须每次都不同：`uk_member_idcard` 钉的是“一证一号”，写死同一个号第二跑就会被拒
# （这是正确约束，不是联跑 bug）。
$idNo = "1101011990{0:D8}" -f (Get-Random -Maximum 99999999)
Invoke-Api POST "/member/me/realname" @{
    realName = "联跑骑手"; idNo = $idNo; smsCode = $member.echoCode
} $login.accessToken | Out-Null
Invoke-Api POST "/swap/rights/grant" @{
    memberId = $login.memberId; times = 5; validDays = 30; remark = "跨进程联跑发放"
} $admin | Out-Null
Write-Host "会员已注册并实名，memberId=$($login.memberId)"

# 3) 起设备侧进程（自动完成物理动作）
$simJar = Join-Path $PSScriptRoot "..\buddy-sim\target\buddy-sim.jar"
$simArgs = @("-jar", $simJar, "--host", "127.0.0.1", "--port", "1883", "--product", $Product,
    "--device", $device, "--secret", $deviceSecret, "--slots", "8",
    "--offer-battery", $offerBattery, "--offer-slot", "1", "--old-battery", $oldBattery,
    "--swap-delay-ms", "700", "--run-seconds", "$($PollSeconds + 30)", "--auto-swap")
$sim = Start-Process -FilePath "java" -ArgumentList $simArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$env:TEMP\xp-sim-$PID.log" -RedirectStandardError "$env:TEMP\xp-sim-$PID-err.log"
try {
    Start-Sleep -Seconds 6
    # 设备在线是建单前提；没连上就建单会被 guard 拒（DEVICE_STALE），那是正确行为。
    # 所以这里轮询在线态而不是盲睡。
    $online = ""
    for ($i = 0; $i -lt 10; $i++) {
        $deviceView = Invoke-Api GET "/swap/devices/$device`?productKey=$Product" $null $admin
        $online = $deviceView.onlineState
        if ($online -eq "ONLINE") { break }
        Start-Sleep -Seconds 2
    }
    if ($online -ne "ONLINE") { throw "设备未上线（onlineState=$online），柜侧日志见 `$env:TEMP\xp-sim-err.log" }
    Write-Host "设备已上线"

    # 4) 建单 + 开归还仓，然后轮询到终态
    $created = Invoke-Api POST "/member/swap/orders" @{ cabinetNo = $cabinet } $login.accessToken
    if (-not $created.accepted) { throw "建单被拒：$($created.rejectReasons -join ', ')" }
    $orderNo = $created.orderNo
    Write-Host "订单 $orderNo 已创建（$($created.displayState)）"
    Invoke-Api POST "/member/swap/orders/$orderNo/start" @{} $login.accessToken | Out-Null

    $final = $null
    for ($i = 0; $i -lt ($PollSeconds / 2); $i++) {
        Start-Sleep -Seconds 2
        $progress = Invoke-Api GET "/member/swap/orders/$orderNo" $null $login.accessToken
        $final = $progress.displayState
        if ($final -in @("SUCCESS", "REJECTED", "CANCELLED")) { break }
        Write-Host "  轮询：$($progress.orderState) / $final"
    }
    Write-Host "最终展示态：$final"
    if ($final -ne "SUCCESS") {
        throw "跨进程联跑未跑成一单：停在 $final（柜侧日志见 `$env:TEMP\xp-sim.log）"
    }
    $rights = Invoke-Api GET "/swap/rights/$($login.memberId)" $null $admin
    if ($rights.times_used -ne 1) { throw "权益实扣不正确：times_used=$($rights.times_used)" }
    Write-Host "联跑通过：权益已实扣 1 次"
} finally {
    # 必须先判 $sim 非空：上一行 Start-Process 失败时它是 null，
    # 不判就会用“Stop-Process 参数为空”把真正的失败原因盖掉（本脚本真遇过）。
    if ($sim -and -not $sim.HasExited) {
        # Start-Process -PassThru 返回的是 System.Diagnostics.Process，字段是 Id 而不是 Pid
        Stop-Process -Id $sim.Id -Force
    }
    if ($sim) {
        Write-Host ("柜侧日志：" + "$env:TEMP\xp-sim-$PID.log")
    }
}
