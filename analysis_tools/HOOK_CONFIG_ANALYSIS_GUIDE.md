# Hook 配置分析指南

> 快速定位微信小程序登录 Hook 类名的完整流程

---

## 📋 目录

- [快速开始](#快速开始)
- [环境配置](#环境配置)
- [分析流程](#分析流程)
- [工具脚本](#工具脚本)
- [常见问题](#常见问题)
- [配置格式](#配置格式)
- [版本差异](#版本差异)

---

## 快速开始

### 一键分析命令

```powershell
# 1. 设置环境
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 2. 反编译 APK
$apk = "微信-8.0.XX.apk"
$version = "8.0.XX"
D:\project\wxcode\tools\jadx\bin\jadx.bat -d "decompiled_$version" --no-res --no-debug-info $apk

# 3. 定位配置（查看下方"核心类定位"）
```

### 核心文件位置

```
反编译结果/
└── sources/
    └── com/tencent/mm/plugin/appbrand/jsapi/auth/
        └── JsApiLogin$LoginTask.java  ← 核心文件
```

---

## 环境配置

### Java 环境

**推荐使用 Android Studio 自带的 JBR**：

```powershell
# 设置 JAVA_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

# 验证
& "$env:JAVA_HOME\bin\java.exe" -version
# 输出: openjdk version "21.0.8" ...

# 添加到 PATH
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

**常见 Java 位置**：
```
C:\Program Files\Java
C:\Program Files\Eclipse Adoptium
C:\Program Files\Android\Android Studio\jbr  ← 推荐
```

### 反编译工具

**jadx 配置**：
```bash
# 工具位置
D:\project\wxcode\tools\jadx\bin\jadx.bat

# 推荐参数
jadx -d <输出目录> --no-res --no-debug-info <APK路径>

# 参数说明
--no-res         # 跳过资源文件，加速反编译
--no-debug-info  # 减少调试信息，减小输出体积
```

---

## 分析流程

### 方法一：静态分析（推荐）

#### 步骤 1：反编译 APK

```powershell
# 设置环境
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 反编译
$apkPath = "D:\project\wx\微信-com.tencent.mm-8.0.49.apk"
$outputDir = "D:\project\wxcode\wxcode_source\decompiled\8.0.49"
$jadxPath = "D:\project\wxcode\tools\jadx\bin\jadx.bat"

& $jadxPath -d $outputDir --no-res --no-debug-info $apkPath
```

#### 步骤 2：定位核心类

打开文件：
```
decompiled_版本/sources/com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiLogin$LoginTask.java
```

#### 步骤 3：提取配置

**搜索关键代码**：

| 配置项 | 搜索模式 | 示例代码 | 提取结果 |
|--------|----------|----------|----------|
| **j1** | `\w+\.\w+\.d\(\)\.f\(` | `u70.k1.d().f(cVar)` | `u70.k1` |
| **c** | `new \w+\.\w+\(` | `new o60.c(str, ...)` | `o60.c` |
| **a1** | `new \w+\(this\)` | `b2 b2Var = new b2(this)` | `com.tencent.mm.plugin.appbrand.jsapi.auth.b2` |
| **a7** | `new \w+\(this,` | `f2 f2Var = new f2(this, b2Var)` | `com.tencent.mm.plugin.appbrand.jsapi.auth.f2` |

**具体代码位置（以 8.0.49 为例）**：

```java
// 第 167 行 - a1 类
b2 b2Var = new b2(this);
// 结果: a1 = com.tencent.mm.plugin.appbrand.jsapi.auth.b2

// 第 177 行 - a7 类
f2 f2Var = new f2(this, b2Var);
// 结果: a7 = com.tencent.mm.plugin.appbrand.jsapi.auth.f2

// 第 179 行 - c 类
o60.c cVar = new o60.c(str, linkedList2, 1, "", "", i15, i16, f2Var);
// 结果: c = o60.c

// 第 185 行 - j1 类
u70.k1.d().f(cVar);
// 结果: j1 = u70.k1
```

#### 步骤 4：更新配置

编辑 `WxLoginHook.java`：

```java
private String jsonString = """
    {
        "8.0.49": {
            "j1": "u70.k1",
            "c": "o60.c",
            "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.b2",
            "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.f2"
        }
    }""";
```

### 方法二：运行时探测

#### 步骤 1：编译安装模块

```powershell
cd D:\project\wxcode\wxcode_source
gradlew assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
```

#### 步骤 2：开启探测

```bash
# 访问 HTML 界面
http://127.0.0.1:8088/?debug

# 或使用 API
curl "http://127.0.0.1:8088/detect?action=start"
```

#### 步骤 3：触发登录

```bash
# 方法一：打开任意小程序

# 方法二：调用 API
curl "http://127.0.0.1:8088/login?appId=wxaa3a999db5d744c6"
```

#### 步骤 4：查看结果

```bash
# 查看日志
adb logcat -s WxLoginHook-Detector

# 查看文件
adb shell cat /sdcard/Android/wxcode/detected_config.json
```

---

## 工具脚本

### 一键分析脚本

创建文件 `analyze_hook.py`：

```python
#!/usr/bin/env python3
"""
Hook 配置自动提取工具
从反编译结果中提取 j1, c, a1, a7 配置

使用方法:
    python analyze_hook.py <反编译目录> [版本]
    
示例:
    python analyze_hook.py decompiled_8.0.49 8.0.49
"""

import re
import sys
from pathlib import Path

def extract_config(decompiled_dir):
    """从反编译结果中提取配置"""
    login_task = Path(decompiled_dir) / "sources/com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiLogin\$LoginTask.java"
    
    if not login_task.exists():
        print(f"❌ 文件不存在: {login_task}")
        return None
    
    content = login_task.read_text(encoding='utf-8')
    
    # 提取 j1: u70.k1.d().f(
    j1_match = re.search(r'(\w+)\.(\w+)\.d\(\)\.f\(', content)
    j1 = f"{j1_match.group(1)}.{j1_match.group(2)}" if j1_match else None
    
    # 提取 c: o60.c cVar = new o60.c(
    c_match = re.search(r'(\w+)\.(\w+)\s+\w+\s*=\s*new\s+\1\.\2\(', content)
    c = f"{c_match.group(1)}.{c_match.group(2)}" if c_match else None
    
    # 提取 a1: b2 b2Var = new b2(this)
    a1_match = re.search(r'(\w+)\s+\w+\s*=\s*new\s+\1\(this\)', content)
    a1 = f"com.tencent.mm.plugin.appbrand.jsapi.auth.{a1_match.group(1)}" if a1_match else None
    
    # 提取 a7: f2 f2Var = new f2(this,
    a7_match = re.search(r'(\w+)\s+\w+\s*=\s*new\s+\1\(this,', content)
    a7 = f"com.tencent.mm.plugin.appbrand.jsapi.auth.{a7_match.group(1)}" if a7_match else None
    
    return {
        "j1": j1,
        "c": c,
        "a1": a1,
        "a7": a7
    }

def main():
    if len(sys.argv) < 2:
        print("使用方法: python analyze_hook.py <反编译目录> [版本]")
        print("示例: python analyze_hook.py decompiled_8.0.49 8.0.49")
        sys.exit(1)
    
    decompiled_dir = sys.argv[1]
    version = sys.argv[2] if len(sys.argv) > 2 else "unknown"
    
    print(f"🔍 分析目录: {decompiled_dir}")
    print(f"📱 版本: {version}")
    
    config = extract_config(decompiled_dir)
    
    if config:
        print("\n" + "="*60)
        print("✅ 配置提取成功")
        print("="*60)
        print("\n【JSON 格式】")
        print('{')
        print(f'    "{version}": {{')
        print(f'        "j1": "{config["j1"] or "未找到"}",')
        print(f'        "c": "{config["c"] or "未找到"}",')
        print(f'        "a1": "{config["a1"] or "未找到"}",')
        print(f'        "a7": "{config["a7"] or "未找到"}"')
        print('    }')
        print('}')
        
        # 检查完整性
        missing = [k for k, v in config.items() if not v]
        if missing:
            print(f"\n⚠️  警告: 以下配置项未找到: {', '.join(missing)}")
        else:
            print("\n✓ 全部配置项已提取")

if __name__ == "__main__":
    main()
```

**使用方法**：

```powershell
# 反编译后运行
python analyze_hook.py decompiled_8.0.49 8.0.49
```

### PowerShell 自动化脚本

创建文件 `auto_analyze.ps1`：

```powershell
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
$outputDir = "D:\project\wxcode\wxcode_source\decompiled_$Version"

Write-Host "🔍 开始分析微信 $Version" -ForegroundColor Green

# 检查文件
if (-not (Test-Path $ApkPath)) {
    Write-Host "❌ APK 文件不存在: $ApkPath" -ForegroundColor Red
    exit 1
}

# 反编译
Write-Host "📦 反编译 APK..." -ForegroundColor Yellow
if (Test-Path $outputDir) {
    Remove-Item $outputDir -Recurse -Force
}

& $jadxPath -d $outputDir --no-res --no-debug-info $ApkPath

if (-not (Test-Path $outputDir)) {
    Write-Host "❌ 反编译失败" -ForegroundColor Red
    exit 1
}

Write-Host "✅ 反编译完成" -ForegroundColor Green

# 提取配置
Write-Host "🔍 提取 Hook 配置..." -ForegroundColor Yellow
python "D:\project\wxcode\wxcode_source\analyze_hook.py" $outputDir $Version

Write-Host "`n✨ 分析完成！" -ForegroundColor Green
Write-Host "📂 反编译结果: $outputDir" -ForegroundColor Cyan
```

**使用方法**：

```powershell
.\auto_analyze.ps1 -ApkPath "D:\project\wx\微信-8.0.49.apk" -Version "8.0.49"
```

---

## 常见问题

### Q1: 反编译报错 "JAVA_HOME not set"

**解决方案**：

```powershell
# 设置 JAVA_HOME
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 验证
java -version
```

### Q2: 反编译内存不足

**解决方案**：

```powershell
# 增加 Java 堆内存
$env:JAVA_OPTS = "-Xmx8g"

# 然后运行 jadx
jadx -d output --no-res app.apk
```

### Q3: 反编译有错误

```
ERROR - finished with errors, count: 161
```

**解决方案**：

这是正常的，不影响核心类分析。继续分析即可。

### Q4: 探测找不到类

**原因**：
- 类未加载（延迟加载）
- 探测条件过于严格

**解决方案**：

使用静态分析（反编译）代替运行时探测。

### Q5: 配置不生效

**检查清单**：

```powershell
# 1. 检查版本号
adb shell dumpsys package com.tencent.mm | Select-String versionName

# 2. 检查配置文件
adb shell cat /sdcard/Android/wxcode/hook_config.json

# 3. 检查日志
adb logcat -s WxLoginHook | Select-String "找不到类"
```

### Q6: 类名混淆规律

**混淆特征**：

| 特征 | 说明 | 示例 |
|------|------|------|
| 包名长度 | 2-3 字符 | `o60`, `u70`, `hm0` |
| 类名长度 | 1-2 字符 | `c`, `k1`, `j1` |
| 方法链 | 静态 + 实例 | `d().f()` |

---

## 配置格式

### JSON 格式

```json
{
    "版本号": {
        "j1": "任务提交器类（有 d() 静态方法）",
        "c": "任务参数类（构造函数参数匹配）",
        "a1": "单参数回调类（构造: LoginTask）",
        "a7": "双参数回调类（构造: LoginTask, 回调对象）"
    }
}
```

### Java 代码格式

```java
private String jsonString = """
    {
        "8.0.49": {"j1": "u70.k1", "c": "o60.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.b2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.f2"},
        "8.0.76": {"j1": "hm0.j1", "c": "cl0.c", "a1": "com.tencent.mm.plugin.appbrand.jsapi.auth.i2", "a7": "com.tencent.mm.plugin.appbrand.jsapi.auth.m2"}
    }""";
```

### 字段说明

| 字段 | 类型 | 说明 | 定位方法 |
|------|------|------|----------|
| **j1** | 任务提交器 | 有静态方法 `d()` 返回对象有方法 `f()` | 搜索 `.d().f(` |
| **c** | 任务参数类 | 构造函数参数: String, LinkedList, int, String, String, int, int, Object | 搜索 `new XX.c(` |
| **a1** | 单参数回调 | 构造函数: `(LoginTask)` | 搜索 `new XX(this)` |
| **a7** | 双参数回调 | 构造函数: `(LoginTask, Object)` | 搜索 `new XX(this,` |

---

## 版本差异

### 混淆位移规则

| 版本范围 | a1 类 | a7 类 | 说明 |
|----------|-------|-------|------|
| 8.0.49 - 8.0.74 | `b2` | `f2` | 原始混淆 |
| 8.0.76+ | `i2` | `m2` | 混淆位移 |

**原因**：微信在新版本中调整了混淆规则，类名整体位移。

### 版本配置对照表

| 版本 | j1 | c | a1 | a7 |
|------|-----|-----|-----|-----|
| 8.0.49 | `u70.k1` | `o60.c` | `b2` | `f2` |
| 8.0.62 | `of0.j1` | `he0.c` | `h2` | `l2` |
| 8.0.70 | `yj0.j1` | `ti0.c` | `h2` | `l2` |
| 8.0.74 | `gm0.j1` | `bl0.c` | `h2` | `l2` |
| 8.0.76 | `hm0.j1` | `cl0.c` | `i2` | `m2` |

---

## 附录

### 相关文件

| 文件 | 说明 |
|------|------|
| [WxLoginHook.java](app/src/main/java/xiaojw/hook/WxLoginHook.java) | Hook 主类 |
| [ClassDetector.java](app/src/main/java/xiaojw/hook/ClassDetector.java) | 运行时探测器 |
| [analyze_hook.py](analyze_hook.py) | 配置提取脚本 |
| [auto_analyze.ps1](auto_analyze.ps1) | 自动化脚本 |

### 参考资料

- [jadx 官方文档](https://github.com/skylot/jadx)
- [Xposed 框架开发指南](https://api.xposed.info/)
- [Android DEX 文件格式](https://source.android.com/devices/tech/dalvik/dex-format)

---

**更新日期**: 2026-07-16
**维护者**: AI Assistant