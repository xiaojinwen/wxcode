---
name: "reverse-skill"
description: "逆向工程工具集。当需要分析 APK、二进制文件、前端 JS、渗透测试、CTF 时调用此 skill。包含 IDA Pro、Frida、radare2、jadx 等工具链。"
---

# Reverse Engineering Skill Router

完整逆向工程工具集，位于: `d:\project\wx\reverse-skill`

## 快速入口

| 任务类型 | 入口文件 |
|----------|----------|
| **APK / Android** | `skills\apk-reverse\SKILL.md` |
| **IDA Pro** | `skills\ida-reverse\SKILL.md` |
| **JS 逆向** | `skills\js-reverse\SKILL.md` |
| **radare2** | `skills\radare2\SKILL.md` |
| **渗透测试** | `skills\pentest-tools\SKILL.md` |
| **移动逆向** | `skills\mobile-reverse\SKILL.md` |

## 工具位置

**reverse-skill 目录**: `d:\project\wx\reverse-skill`

### 核心文件

| 文件 | 说明 |
|------|------|
| `README_AI.md` | AI 引导文档（首先阅读） |
| `RULES.md` | 全局规则和行为链 |
| `skills\SKILL.md` | 主控入口 |
| `skills\routing.md` | 路由表 |
| `skills\tool-index.md` | 工具索引 |

### 常用工具路径

| 工具 | 路径 |
|------|------|
| jadx | `D:\project\wxcode\tools\jadx\bin\jadx.bat` |
| IDA Pro | `skills\ida-reverse\scripts\start.ps1` |
| Frida | `skills\apk-reverse\scripts\frida-run.ps1` |

## 使用流程

```
1. 阅读 README_AI.md
2. 阅读 RULES.md
3. 阅读 skills/SKILL.md
4. 根据 routing.md 进入对应子模块
5. 执行任务
```

## 集成工具

### 本地工具（已配置）

| 工具 | 路径 | 状态 |
|------|------|------|
| jadx | `D:\project\wxcode\tools\jadx\` | ✓ 可用 |
| Java | `C:\Program Files\Android\Android Studio\jbr` | ✓ 可用 |

### 需要时自举

```powershell
# 自举缺失工具
powershell -File "d:\project\wx\reverse-skill\skills\scripts\bootstrap-reverse.ps1" -Capability @('jadx','frida','radare2')
```

## 当前项目分析工具

微信 Hook 配置分析工具位于: `analysis_tools/`

| 工具 | 文件 | 说明 |
|------|------|------|
| 一键批处理 | `extract_hook_config.bat` | Windows 一键分析 |
| PowerShell | `auto_analyze.ps1` | PowerShell 脚本 |
| Python 增强版 | `analyze_hook_enhanced.py` | 多版本支持 |

## 参考文档

- [reverse-skill README](d:\project\wx\reverse-skill\README.md)
- [reverse-skill AI Guide](d:\project\wx\reverse-skill\README_AI.md)
- [本工具分析指南](analysis_tools/HOOK_CONFIG_ANALYSIS_GUIDE.md)