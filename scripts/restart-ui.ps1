[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$port = 3000
$dir = "c:\Users\lrs\Desktop\sx\buddy-ui"

Write-Host "===== restart buddy-ui dev server ====="
$conns = Get-NetTCPConnection -State Listen -LocalPort $port -EA SilentlyContinue
foreach ($c in $conns) {
    try {
        Stop-Process -Id $c.OwningProcess -Force -EA Stop
        Write-Host "  freed port $port pid=$($c.OwningProcess)"
    } catch {
        Write-Host "  could not stop pid=$($c.OwningProcess): $_"
    }
}
Start-Sleep 3
$logDir = Join-Path $dir '.logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Start-Process npm.cmd -ArgumentList 'run', 'dev' -WorkingDirectory $dir -RedirectStandardOutput "$logDir\dev.log" -RedirectStandardError "$logDir\dev_err.log" -WindowStyle Hidden

$up = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $r = Invoke-WebRequest "http://localhost:$port" -UseBasicParsing -TimeoutSec 5 -EA Stop
        if ($r.StatusCode -eq 200) { $up = $true; break }
    } catch { }
    Start-Sleep 2
}
if ($up) { Write-Host "[OK] UI dev server UP on $port" } else { Write-Host "[FAIL] UI not responding"; Get-Content "$logDir\dev.log" -Tail 20 }
