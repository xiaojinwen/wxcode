# Hook 配置分析工具

微信小程序登录 Hook 配置自动提取工具集。

## 📂 目录结构

```
analysis_tools/
├── README.md                      # 本文件
├── HOOK_CONFIG_ANALYSIS_GUIDE.md  # 完整分析指南
├── extract_hook_config.bat        # Windows 一键批处理脚本
├── auto_analyze.ps1               # PowerShell 脚本
├── analyze_hook.py                # Python 基础版提取脚本
└── analyze_hook_enhanced.py       # Python 增强版提取脚本
```

## 🚀 快速开始

### 方法一：拖拽 APK 到脚本（Windows）

```
将 APK 文件拖到 extract_hook_config.bat 上
```

### 方法二：命令行运行

```powershell
# 批处理脚本
.\extract_hook_config.bat "D:\project\wx\微信-8.0.49.apk" "8.0.49"

# PowerShell 脚本
.\auto_analyze.ps1 -ApkPath "D:\project\wx\微信-8.0.49.apk" -Version "8.0.49"
```

### 方法三：Python 脚本（仅提取）

如果已经有反编译结果：

```powershell
# 基础版
python analyze_hook.py ../decompiled 8.0.49

# 增强版
python analyze_hook_enhanced.py ../decompiled 8.0.49 --verbose
```

## 📋 工作流程

```
1. 检查环境（Java、jadx、Python）
2. 反编译 APK 到 ../decompiled 目录
3. 提取 Hook 配置（j1, c, a1, a7）
4. 输出 JSON 格式配置
5. 自动打开反编译结果目录
```

## 📂 输出目录

反编译结果按版本输出到：

```
wxcode_source/
└── decompiled/           # 反编译结果目录
    ├── 8.0.49/           # 版本 8.0.49 的反编译结果
    │   └── sources/
    │       └── com/tencent/mm/plugin/appbrand/jsapi/auth/
    │           └── JsApiLogin$LoginTask.java
    ├── 8.0.76/           # 版本 8.0.76 的反编译结果
    └── ...
```

## 🔧 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| Java | 17+ | Android Studio 自带的 JBR |
| jadx | 最新 | D:\project\wxcode\tools\jadx |
| Python | 3.6+ | 提取脚本 |

## 📚 详细文档

查看 [HOOK_CONFIG_ANALYSIS_GUIDE.md](HOOK_CONFIG_ANALYSIS_GUIDE.md) 获取：
- 环境配置
- 分析流程
- 常见问题
- 版本差异
- 最佳实践

## ⚙️ 版本特定配置

增强版脚本支持版本特定配置：

| 版本 | a1 类 | a7 类 |
|------|-------|-------|
| 8.0.49 - 8.0.74 | `b2` | `f2` |
| 8.0.76+ | `i2` | `m2` |

## 🔗 相关文件

- [WxLoginHook.java](../app/src/main/java/xiaojw/hook/WxLoginHook.java) - Hook 主类
- [ClassDetector.java](../app/src/main/java/xiaojw/hook/ClassDetector.java) - 运行时探测器

---
**更新日期**: 2026-07-17