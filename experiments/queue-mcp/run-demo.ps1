param(
    [int]$Port = 18082,
    [string]$Model = 'qwen2.5:7b',
    [string]$OutputDirectory = '',
    [switch]$KeepServer
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repo ('output/queue-mcp/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$outDir = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$jar = Join-Path $repo 'app/build/libs/ses-scenario-server.jar'
if (!(Test-Path -LiteralPath $jar)) { throw '먼저 .\gradlew.bat :app:bootJar 로 서버를 빌드하세요.' }
$server = $null
try {
    $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) { throw "포트 $Port 가 사용 중입니다. -Port 로 다른 포트를 지정하세요." }
    $server = Start-Process -FilePath 'java' -ArgumentList @('-jar', ('"' + $jar + '"'), '--spring.profiles.active=memory', '--llm.provider=disabled', "--server.port=$Port") -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $outDir 'server.stdout.log') -RedirectStandardError (Join-Path $outDir 'server.stderr.log')
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ($server.HasExited) { throw '서버 시작 실패. 출력 폴더의 server 로그를 확인하세요.' }
        try {
            $health = Invoke-RestMethod "http://localhost:$Port/actuator/health" -TimeoutSec 1
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch {}
        Start-Sleep -Seconds 1
    }
    if (!$ready) { throw '서버 시작 시간 초과' }
    Get-FileHash -LiteralPath $jar -Algorithm SHA256 | ConvertTo-Json | Set-Content (Join-Path $outDir 'server-build.json') -Encoding utf8
    & python (Join-Path $PSScriptRoot 'experiment.py') demo --server "http://localhost:$Port" --model $Model --output $outDir
    if ($LASTEXITCODE -ne 0) { throw '통합 실험 실패' }
    & python (Join-Path $PSScriptRoot 'verify_live.py') $outDir
    if ($LASTEXITCODE -ne 0) { throw '추가 검증 실패' }
    Write-Host "완료: $outDir/REPORT.md"
} finally {
    if ($server -and !$KeepServer -and !$server.HasExited) { Stop-Process -Id $server.Id }
    if ($server -and $KeepServer) { Write-Host "서버 PID $($server.Id), 포트 $Port 를 유지합니다." }
}
