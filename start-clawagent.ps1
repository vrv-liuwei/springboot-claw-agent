<#
.SYNOPSIS
  Build and start the local ClawAgent server on Windows.

.EXAMPLE
  .\start-clawagent.ps1

.EXAMPLE
  .\start-clawagent.ps1 -Port 17891 -Background
#>

[CmdletBinding()]
param(
    [int]$Port = 17891,
    [switch]$SkipFrontendBuild,
    [switch]$SkipServerBuild,
    [switch]$Background,
    [int]$HealthTimeoutSeconds = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Output "[clawagent] $Message"
}

function Resolve-CommandPath {
    param(
        [string]$Name,
        [string[]]$Fallbacks
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    foreach ($candidate in $Fallbacks) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) {
            return $candidate
        }
    }
    throw "未找到命令 $Name。请安装后加入 PATH，或检查本地工具路径。"
}

function Test-PortAvailable {
    param([int]$LocalPort)

    $connection = Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue
    if ($connection) {
        $owners = ($connection | Select-Object -ExpandProperty OwningProcess -Unique) -join ", "
        throw "端口 $LocalPort 已被占用，进程 PID：$owners。请先停止占用进程，或使用 -Port 指定其他端口。"
    }
}

function Wait-Health {
    param(
        [string]$Url,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $Url -TimeoutSec 3
            if ($response.status -eq "UP") {
                return $true
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

$javaFallback = @()
if ($env:JAVA_HOME) {
    $javaFallback += (Join-Path $env:JAVA_HOME "bin\java.exe")
}
$javaFallback += "D:\tools\Java\64\jdk17.0.7\bin\java.exe"
$mavenFallback = @("D:\tools\Java\apache-maven-3.6.3\bin\mvn.cmd")

$java = Resolve-CommandPath -Name "java" -Fallbacks $javaFallback
$mvn = Resolve-CommandPath -Name "mvn" -Fallbacks $mavenFallback

Write-Step "repo: $repoRoot"
Write-Step "java: $java"
Write-Step "maven: $mvn"
Test-PortAvailable -LocalPort $Port

if (-not $env:DEEPSEEK_API_KEY) {
    Write-Warning "DEEPSEEK_API_KEY 未设置。服务可以启动，但默认聊天模型可能无法调用。"
}
if (-not $env:SILICONFLOW_API_KEY) {
    Write-Warning "SILICONFLOW_API_KEY 未设置。向量记忆或 SiliconFlow 模型可能无法调用。"
}

if (-not $SkipFrontendBuild) {
    Resolve-CommandPath -Name "npm" -Fallbacks @() | Out-Null
    Write-Step "building admin frontend"
    Push-Location (Join-Path $repoRoot "claw-agent-admin")
    try {
        npm run build
        if ($LASTEXITCODE -ne 0) {
            throw "管理台前端构建失败，exitCode=$LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not $SkipServerBuild) {
    Write-Step "packaging server"
    & $mvn -pl claw-agent-server -am package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "服务端打包失败，exitCode=$LASTEXITCODE"
    }
}

$jar = Join-Path $repoRoot "claw-agent-server\target\claw-agent-server-0.1.0-SNAPSHOT.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    throw "未找到服务 Jar：$jar。请去掉 -SkipServerBuild 后重新执行。"
}

$javaArgs = @("-jar", $jar, "--server.port=$Port")
$adminUrl = "http://localhost:$Port/admin/index.html"
$healthUrl = "http://localhost:$Port/api/v1/health"

if ($Background) {
    $logDir = Join-Path $repoRoot "logs"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $out = Join-Path $logDir "clawagent.out.log"
    $err = Join-Path $logDir "clawagent.err.log"
    Write-Step "starting server in background"
    $process = Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $repoRoot -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    Write-Step "pid: $($process.Id)"
    Write-Step "logs: $out"
    Write-Step "admin: $adminUrl"
    Write-Step "health: $healthUrl"
    Write-Step "waiting for health, timeout: ${HealthTimeoutSeconds}s"
    if (Wait-Health -Url $healthUrl -TimeoutSeconds $HealthTimeoutSeconds) {
        Write-Step "server is healthy"
    } else {
        Write-Warning "服务未在 ${HealthTimeoutSeconds}s 内变为健康。请查看日志：$out / $err"
    }
    exit 0
}

Write-Step "starting server in foreground"
Write-Step "admin: $adminUrl"
Write-Step "health: $healthUrl"
& $java @javaArgs
