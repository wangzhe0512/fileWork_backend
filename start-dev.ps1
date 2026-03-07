# ============================================================
# 本机开发环境一键启动脚本
# 用途：检查并启动 MySQL/Redis 服务，然后运行 Spring Boot 后端
# 使用：右键以管理员身份运行，或在 PowerShell 中执行 .\start-dev.ps1
# ============================================================

$ErrorActionPreference = "Stop"

Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  企业文件处理平台 - 本地开发启动脚本" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""

# ------ 1. 检查 Java ------
Write-Host "[1/4] 检查 Java 环境..." -ForegroundColor Yellow
try {
    $javaVersion = java -version 2>&1
    Write-Host "      OK: $($javaVersion[0])" -ForegroundColor Green
} catch {
    Write-Host "      ERROR: 未找到 Java，请先安装 JDK 17" -ForegroundColor Red
    Write-Host "             运行: winget install EclipseAdoptium.Temurin.17.JDK" -ForegroundColor Gray
    exit 1
}

# ------ 2. 检查 Maven ------
Write-Host "[2/4] 检查 Maven 环境..." -ForegroundColor Yellow
try {
    $mvnVersion = mvn -version 2>&1
    Write-Host "      OK: $($mvnVersion[0])" -ForegroundColor Green
} catch {
    Write-Host "      ERROR: 未找到 Maven，请先安装 Maven" -ForegroundColor Red
    Write-Host "             运行: winget install Apache.Maven" -ForegroundColor Gray
    exit 1
}

# ------ 3. 启动 MySQL ------
Write-Host "[3/4] 检查 MySQL 服务..." -ForegroundColor Yellow

# 尝试常见的 MySQL 服务名
$mysqlServiceNames = @("MySQL80", "MySQL", "mysql")
$mysqlStarted = $false

foreach ($svcName in $mysqlServiceNames) {
    $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
    if ($svc) {
        if ($svc.Status -eq "Running") {
            Write-Host "      OK: MySQL 服务 ($svcName) 已在运行" -ForegroundColor Green
        } else {
            Write-Host "      启动 MySQL 服务 ($svcName)..." -ForegroundColor Yellow
            try {
                Start-Service -Name $svcName
                Write-Host "      OK: MySQL 服务已启动" -ForegroundColor Green
            } catch {
                Write-Host "      ERROR: 无法启动 MySQL 服务，请以管理员身份运行此脚本" -ForegroundColor Red
                exit 1
            }
        }
        $mysqlStarted = $true
        break
    }
}

if (-not $mysqlStarted) {
    Write-Host "      WARN: 未找到 MySQL 服务，请确认 MySQL 已安装" -ForegroundColor Red
    Write-Host "            运行: winget install Oracle.MySQL" -ForegroundColor Gray
    $confirm = Read-Host "      是否继续启动后端？(y/N)"
    if ($confirm -ne "y" -and $confirm -ne "Y") {
        exit 1
    }
}

# ------ 4. 启动 Redis/Memurai ------
Write-Host "[4/4] 检查 Redis 服务..." -ForegroundColor Yellow

$redisServiceNames = @("Memurai", "Redis", "redis-server")
$redisStarted = $false

foreach ($svcName in $redisServiceNames) {
    $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
    if ($svc) {
        if ($svc.Status -eq "Running") {
            Write-Host "      OK: Redis 服务 ($svcName) 已在运行" -ForegroundColor Green
        } else {
            Write-Host "      启动 Redis 服务 ($svcName)..." -ForegroundColor Yellow
            try {
                Start-Service -Name $svcName
                Write-Host "      OK: Redis 服务已启动" -ForegroundColor Green
            } catch {
                Write-Host "      ERROR: 无法启动 Redis 服务，请以管理员身份运行此脚本" -ForegroundColor Red
                exit 1
            }
        }
        $redisStarted = $true
        break
    }
}

if (-not $redisStarted) {
    Write-Host "      WARN: 未找到 Redis/Memurai 服务，请确认已安装" -ForegroundColor Red
    Write-Host "            运行: winget install Memurai.Memurai" -ForegroundColor Gray
    $confirm = Read-Host "      是否继续启动后端？(y/N)"
    if ($confirm -ne "y" -and $confirm -ne "Y") {
        exit 1
    }
}

# ------ 5. 启动 Spring Boot ------
Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  启动 Spring Boot 后端..." -ForegroundColor Cyan
Write-Host "  接口地址: http://localhost:8080/api" -ForegroundColor Cyan
Write-Host "  接口文档: http://localhost:8080/api/doc.html" -ForegroundColor Cyan
Write-Host "  按 Ctrl+C 停止服务" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""

# 切换到脚本所在目录（项目根目录）
Set-Location $PSScriptRoot

mvn spring-boot:run
