<#
.SYNOPSIS
    自动分析微信 APK 并提取 Hook 配置

.PARAMETER ApkPath
    微信 APK 文件路径

.PARAMETER Version
    微信版本号

.EXAMPLE
    .\auto_analyze.ps1 -ApkPath "D:\project\wx\微信-8.0.49.apk" -Version "8.0.49"
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$ApkPath,
    
    [Parameter(Mandatory=$true)]
    [string]$Version
)

# 设置环境
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 工具路径
$jadxPath = "D:\project\wxcode\tools\jadx\bin\jadx.bat"

# 统一输出到 decompiled/VERSION 目录
$outputDir = "$PSScriptRoot\..\decompiled\$Version"

Write-Host "======================================" -ForegroundColor Cyan
Write-Host "🔍 微信 Hook 配置自动分析工具" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "📱 微信版本: $Version" -ForegroundColor Green
Write-Host "📦 APK 路径: $ApkPath" -ForegroundColor Green
Write-Host "📂 输出目录: $outputDir" -ForegroundColor Green
Write-Host ""

# 检查文件
if (-not (Test-Path $ApkPath)) {
    Write-Host "❌ APK 文件不存在: $ApkPath" -ForegroundColor Red
    exit 1
}

# 检查 Java
Write-Host "🔍 检查 Java 环境..." -ForegroundColor Yellow
try {
    $javaVersion = & "$env:JAVA_HOME\bin\java.exe" -version 2>&1 | Select-String "version"
    Write-Host "✅ Java 环境: $javaVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ Java 环境未找到，请设置 JAVA_HOME" -ForegroundColor Red
    exit 1
}

# 检查 jadx
if (-not (Test-Path $jadxPath)) {
    Write-Host "❌ jadx 工具不存在: $jadxPath" -ForegroundColor Red
    exit 1
}

# 反编译
Write-Host ""
Write-Host "📦 开始反编译 APK..." -ForegroundColor Yellow
Write-Host "⏳ 这可能需要 5-10 分钟，请耐心等待..." -ForegroundColor Gray

if (Test-Path $outputDir) {
    Write-Host "🗑️  删除旧的反编译结果..." -ForegroundColor Gray
    Remove-Item $outputDir -Recurse -Force
}

$startTime = Get-Date

& $jadxPath -d $outputDir --no-res --no-debug-info $ApkPath 2>&1 | ForEach-Object {
    if ($_ -match "progress: (\d+) of (\d+)") {
        $percent = [math]::Round([int]$matches[1] / [int]$matches[2] * 100)
        Write-Host "`r⏳ 反编译进度: $percent%" -NoNewline -ForegroundColor Yellow
    }
}

Write-Host ""

if (-not (Test-Path $outputDir)) {
    Write-Host "❌ 反编译失败" -ForegroundColor Red
    exit 1
}

$endTime = Get-Date
$duration = ($endTime - $startTime).TotalSeconds

Write-Host "✅ 反编译完成 (耗时: $duration 秒)" -ForegroundColor Green
Write-Host "📂 输出目录: $outputDir" -ForegroundColor Cyan

# 提取配置
Write-Host ""
Write-Host "🔍 提取 Hook 配置..." -ForegroundColor Yellow

$scriptPath = "$PSScriptRoot\analyze_hook_enhanced.py"
if (-not (Test-Path $scriptPath)) {
    $scriptPath = "$PSScriptRoot\analyze_hook.py"
}

if (Test-Path $scriptPath) {
    python $scriptPath $outputDir $Version
} else {
    Write-Host "⚠️  analyze_hook.py 不存在，请手动分析" -ForegroundColor Yellow
    Write-Host "📂 请打开: $outputDir\sources\com\tencent\mm\plugin\appbrand\jsapi\auth\JsApiLogin`$LoginTask.java" -ForegroundColor Cyan
}

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "✨ 分析完成！" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor Cyan