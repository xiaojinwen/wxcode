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
    login_task = Path(decompiled_dir) / "sources/com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiLogin$LoginTask.java"
    
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
        
        print("\n【Java 格式】")
        print(f'"{version}": {{')
        print(f'    "j1": "{config["j1"] or "未找到"}",')
        print(f'    "c": "{config["c"] or "未找到"}",')
        print(f'    "a1": "{config["a1"] or "未找到"}",')
        print(f'    "a7": "{config["a7"] or "未找到"}"')
        print('}')
        
        # 检查完整性
        missing = [k for k, v in config.items() if not v]
        if missing:
            print(f"\n⚠️  警告: 以下配置项未找到: {', '.join(missing)}")
        else:
            print("\n✓ 全部配置项已提取")

if __name__ == "__main__":
    main()