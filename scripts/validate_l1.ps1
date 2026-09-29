[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$base = "http://localhost:8200/api"
$script:headers = @{}
$backendLog = "c:\Users\lrs\Desktop\sx\buddy\backend.log"

function Wait-Port($port, $timeoutSec) {
    $elapsed = 0
    while ($elapsed -lt $timeoutSec) {
        $out = netstat -ano 2>$null | Select-String ":$port "
        if ($out) { return $true }
        Start-Sleep -Seconds 2
        $elapsed += 2
    }
    return $false
}

function Free-Port($port) {
    # 用 Get-NetTCPConnection 直接拿监听该端口的进程，比解析 netstat 文本稳
    $conns = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    $pids = @($conns | Select-Object -ExpandProperty OwningProcess -Unique | Where-Object { $_ -gt 0 })
    foreach ($p in $pids) {
        # /T 连同子进程树一起终止：spring-boot:run 会 fork 独立 java 子进程，
        # 只杀父进程会残留占端口的子进程（这正是之前"找不到进程"的根因）
        cmd /c "taskkill /F /T /PID $p" 2>$null | Out-Null
        Write-Host "  释放端口 $port 占用 PID=$p（含子进程树）"
    }
    # 轮询确认端口已释放，最多再等 8 秒；不再无差别杀所有 java.exe，避免误伤本机其它 Java 程序
    $waited = 0
    while ($waited -lt 8 -and (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)) {
        Start-Sleep -Seconds 1
        $waited += 1
    }
}

function Req($method, $path, $body) {
    if ($body) {
        return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $script:headers -ContentType "application/json" -Body $body -ErrorAction Stop
    }
    return Invoke-RestMethod -Uri "$base$path" -Method $method -Headers $script:headers -ErrorAction Stop
}

function Check($cond, $ok, $bad) {
    if ($cond) { Write-Host "  [OK] $ok" } else { Write-Host "  [FAIL] $bad" }
}

function Show($resp, $label) {
    Write-Host "    -> code=$($resp.code) msg=$($resp.message)"
}

Write-Host "===== 启动后端（clean 强制重编译）====="
Free-Port 8200
Start-Process -FilePath "mvn.cmd" -ArgumentList "clean","spring-boot:run","-o" -WorkingDirectory "c:\Users\lrs/Desktop/sx/buddy" -RedirectStandardOutput $backendLog -RedirectStandardError "c:\Users/lrs/Desktop/sx/buddy\backend_err.log" -WindowStyle Hidden
if (-not (Wait-Port 8200 120)) {
    Write-Host "[FAIL] 后端 120 秒内未监听 8200"
    Write-Host "--- Started? ---"; Select-String -Path $backendLog -Pattern "Started BuddyApplication" | Select-Object -Last 1
    Write-Host "--- ERROR? ---"; Select-String -Path $backendLog -Pattern "ERROR|APPLICATION FAILED" | Select-Object -Last 5
    exit 1
}
Write-Host "[OK] 后端已监听 8200"
# 端口监听不等于 Spring 完全就绪，轮询 /actuator/health 直到 UP（dev 已放行）
$springUp = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $h = Invoke-RestMethod -Uri "$base/actuator/health" -ErrorAction Stop
        if ($h.status -eq "UP") { $springUp = $true; break }
    } catch {}
    Start-Sleep -Seconds 2
}
if (-not $springUp) {
    Write-Host "[FAIL] Spring 未就绪，日志末尾："; Get-Content $backendLog -Tail 20
    exit 1
}
Write-Host "[OK] Spring 已就绪(UP)"

Write-Host "===== L1 闭环验证 ====="

# 1. 登录
try {
    $loginBody = '{"username":"admin","password":"Admin@123456"}'
    $login = Invoke-RestMethod -Uri "$base/auth/login" -Method Post -ContentType "application/json" -Body $loginBody -ErrorAction Stop
    $token = $login.data.token
    $script:headers = @{ Authorization = "Bearer $token" }
    Check ($login.code -eq 200) "登录成功, token长度=$($token.Length)" "登录失败 code=$($login.code) msg=$($login.message)"
} catch {
    Write-Host "  [FAIL] 登录异常: $_"; exit 1
}

# 2. 操作日志
$pageBody = '{"pageNum":1,"pageSize":10}'
$ol = Req Post "/sys/operate-log/page" $pageBody
Check ($ol.code -eq 200 -and $ol.data.total -gt 0) "操作日志可查, total=$($ol.data.total)" "操作日志失败 code=$($ol.code)"

# 3. 部门树（数据权限）
$dept = Req Get "/sys/dept/tree" $null
Check ($dept.code -eq 200 -and $dept.data.Count -gt 0) "部门树返回, count=$($dept.data.Count)" "部门树失败 code=$($dept.code)"

# 4. 用户分页（@DataScope 切面）
$up = Req Post "/sys/user/page" $pageBody
Check ($up.code -eq 200 -and $up.data.records.Count -gt 0) "用户分页返回, total=$($up.data.total)" "用户分页失败 code=$($up.code)"

# 5. 任务分页
$jp = Req Post "/sys/job/page" $pageBody
Check ($jp.code -eq 200) "任务分页返回, total=$($jp.data.total)" "任务分页失败 code=$($jp.code)"

# 6. 文件上传（curl，PS5.1 不支持 -Form）
$tmp = New-TemporaryFile
$txt = [System.IO.Path]::ChangeExtension($tmp.FullName, ".txt")
Move-Item $tmp.FullName $txt -Force
Set-Content -Path $txt -Value "buddy L1 validate $(Get-Date)" -Encoding ASCII
try {
    $upJson = curl.exe -s -X POST "$base/sys/file/upload" -H "Authorization: Bearer $token" -F "file=@$txt;type=text/plain"
    $fu = $upJson | ConvertFrom-Json
    Check ($fu.code -eq 200) "文件上传成功, id=$($fu.data.id), name=$($fu.data.originalName)" "文件上传失败: $($fu.message)"
    try {
        $dl = curl.exe -s -o "$txt.dl" -w "%{http_code}" -H "Authorization: Bearer $token" "$base/sys/file/download/$($fu.data.id)"
        $dlCode = $dl
        $dlSize = (Get-Item "$txt.dl" -ErrorAction SilentlyContinue).Length
        Check ($dlCode -eq 200 -and $dlSize -gt 0) "文件下载回环 OK, http=$dlCode, size=$dlSize" "文件下载失败 http=$dlCode"
    } finally { Remove-Item "$txt.dl" -ErrorAction SilentlyContinue }
} finally {
    Remove-Item $txt -ErrorAction SilentlyContinue
}

# 7. Quartz 动态任务：创建 -> 立即执行 -> 删除（用创建前后 id 差集定位，规避中文乱码）
$jobBody = '{"jobName":"L1闭环验证","jobGroup":"DEFAULT","beanName":"demoTask","params":"hello-l1","cronExpression":"0 0 0 1 1 ? 2099","status":0,"concurrent":1,"misfirePolicy":1}'
$before = (Req Post "/sys/job/page" $pageBody).data.records.id
$cj = Req Post "/sys/job" $jobBody
Check ($cj.code -eq 200) "任务创建: $($cj.message)" "任务创建失败: code=$($cj.code) msg=$($cj.message)"
$after = (Req Post "/sys/job/page" $pageBody).data.records
$newJob = $after | Where-Object { $before -notcontains $_.id } | Select-Object -First 1
if ($newJob) {
    $jobId = $newJob.id
    $rj = Req Put "/sys/job/run/$jobId" $null
    Check ($rj.code -eq 200) "任务立即执行(jobId=$jobId): $($rj.message)" "立即执行失败 code=$($rj.code)"
    Start-Sleep -Seconds 5
    $dj = Req Delete "/sys/job" ('["' + [string]$jobId + '"]')
    Check ($dj.code -eq 200) "任务删除: $($dj.message)" "任务删除失败 code=$($dj.code) msg=$($dj.message)"
} else {
    Write-Host "  [FAIL] 未找到刚创建的任务，跳过执行/删除"
}

# 8. 防重复提交
$ts = [DateTimeOffset]::Now.ToUnixTimeSeconds()
$userBody = "{`"username`":`"rt_$ts`",`"password`":`"Test@123456`",`"nickname`":`"防重测试`",`"roleIds`":[1],`"deptId`":100,`"status`":0}"
$r1 = Req Post "/sys/user" $userBody
Check ($r1.code -eq 200) "防重-第1次新增成功: $($r1.message)" "第1次新增失败 code=$($r1.code) msg=$($r1.message)"
$r2 = Req Post "/sys/user" $userBody
if ($r2.code -eq 1404) {
    Check $true "防重-第2次被拦截: code=1404, msg=$($r2.message)" ""
} else {
    Check $false "" "防重-第2次未被拦截: code=$($r2.code) msg=$($r2.message)"
}
try {
    $cleanQ = "{`"pageNum`":1,`"pageSize`":100,`"keyword`":`"rt_$ts`"}"
    $cq = Req Post "/sys/user/page" $cleanQ
    $ids = @($cq.data.records | Where-Object { $_.username -like "rt_$ts*" } | ForEach-Object { $_.id })
    if ($ids.Count -gt 0) {
        Req Delete "/sys/user" ('[' + ($ids -join ',') + ']') | Out-Null
        Write-Host "  [OK] 清理测试用户: 删除 $($ids.Count) 个"
    }
} catch { Write-Host "  [WARN] 清理测试用户跳过: $_" }

# 9. 后端日志中 Quartz 执行痕迹：匹配 ASCII 结构标识（类名 + beanName），
# 不用中文子串——日志文件编码与脚本断言编码不一致时，中文匹配会假阴性
$logLine = Select-String -Path $backendLog -Pattern "BuddyQuartzJob.*demoTask" | Select-Object -Last 1
if ($logLine) { Check $true "Quartz执行日志确认: $($logLine.Line.Trim())" "" } else { Check $false "" "未在后端日志找到任务执行记录" }

Write-Host "===== 后端日志诊断 ====="
Select-String -Path $backendLog -Pattern "任务执行|示例任务|triggerJob|ERROR|Exception|Quartz|请求体" | Select-Object -Last 20 | ForEach-Object { Write-Host $_.Line }

Write-Host "===== L1 闭环验证结束 ====="
