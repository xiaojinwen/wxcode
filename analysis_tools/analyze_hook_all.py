#!/usr/bin/env python3
"""
Hook 配置提取脚本 - 输出所有候选
从反编译的微信 APK 中提取 Hook 配置，输出所有可能的候选类
"""

import re
import sys
import json
from pathlib import Path

def extract_all_candidates(decompiled_dir):
    """提取所有候选配置"""
    login_task = Path(decompiled_dir) / "sources/com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiLogin$LoginTask.java"
    
    if not login_task.exists():
        print(f"❌ Error: {login_task} not found")
        return None
    
    content = login_task.read_text(encoding='utf-8')
    
    result = {
        "j1": [],
        "c": [],
        "a1": [],
        "a7": []
    }
    
    print("=" * 60)
    print("开始提取所有候选配置")
    print("=" * 60)
    print()
    
    # ================================
    # 1. 提取 j1 候选（任务提交器）
    # ================================
    print("【j1 类候选】")
    print("-" * 60)
    
    # 模式1: XX.YY.d().g(cVar)
    j1_patterns = [
        r'(\w+)\.(\w+)\.d\(\)\.g\(cVar\)',
        r'(\w+)\.(\w+)\.d\(\)\.f\(cVar\)',
        r'(\w+)\.(\w+)\.getInstance\(\)\.submit\(',
    ]
    
    for pattern in j1_patterns:
        matches = re.findall(pattern, content)
        for match in matches:
            pkg, cls = match
            full_name = f"{pkg}.{cls}"
            if full_name not in result["j1"]:
                result["j1"].append(full_name)
                print(f"  ✓ {full_name} (模式: {pattern[:30]}...)")
    
    if not result["j1"]:
        print("  ❌ 未找到 j1 候选")
    
    print()
    
    # ================================
    # 2. 提取 c 候选（任务参数类）
    # ================================
    print("【c 类候选】")
    print("-" * 60)
    
    # 模式1: XX.YY cVar = new XX.YY(...)
    c_patterns = [
        r'(\w+)\.(\w+)\s+cVar\s*=\s*new\s+\1\.\2\(',
        r'(\w+)\.(\w+)\s+\w+\s*=\s*new\s+\1\.\2\([^)]*\)',
    ]
    
    for pattern in c_patterns:
        matches = re.findall(pattern, content)
        for match in matches:
            pkg, cls = match
            full_name = f"{pkg}.{cls}"
            if full_name not in result["c"]:
                result["c"].append(full_name)
                print(f"  ✓ {full_name} (构造函数匹配)")
    
    if not result["c"]:
        print("  ❌ 未找到 c 候选")
    
    print()
    
    # ================================
    # 3. 提取 a1/a7 候选（回调类）
    # ================================
    print("【回调类候选】")
    print("-" * 60)
    
    # 查找所有 new XX(this) 或 new XX(this, ...) 的模式
    callback_pattern = r'(\w+)\s+(\w+)\s*=\s*new\s+(\w+)\((this[^)]*)\)'
    callback_matches = re.findall(callback_pattern, content)
    
    for match in callback_matches:
        cls_name, var_name, _, params = match
        full_name = f"com.tencent.mm.plugin.appbrand.jsapi.auth.{cls_name}"
        
        # 根据参数数量分类
        param_count = len(params.split(','))
        
        if param_count == 1 and "this" in params:
            # 单参数：a1
            if full_name not in result["a1"]:
                result["a1"].append(full_name)
                print(f"  ✓ a1 候选: {cls_name} (参数: {params.strip()})")
        elif param_count == 2:
            # 双参数：a7
            if full_name not in result["a7"]:
                result["a7"].append(full_name)
                print(f"  ✓ a7 候选: {cls_name} (参数: {params.strip()})")
    
    if not result["a1"]:
        print("  ❌ 未找到 a1 候选")
    if not result["a7"]:
        print("  ❌ 未找到 a7 候选")
    
    print()
    
    return result

def print_config_combinations(candidates, version):
    """输出所有可能的配置组合"""
    print("=" * 60)
    print("所有可能的配置组合")
    print("=" * 60)
    print()
    
    # 生成所有组合
    j1_list = candidates["j1"] if candidates["j1"] else ["未找到"]
    c_list = candidates["c"] if candidates["c"] else ["未找到"]
    a1_list = candidates["a1"] if candidates["a1"] else ["未找到"]
    a7_list = candidates["a7"] if candidates["a7"] else ["未找到"]
    
    print(f"j1 候选数: {len(j1_list)}")
    print(f"c 候选数: {len(c_list)}")
    print(f"a1 候选数: {len(a1_list)}")
    print(f"a7 候选数: {len(a7_list)}")
    print()
    
    # 输出所有组合
    print("=" * 60)
    print("配置组合列表")
    print("=" * 60)
    
    config_index = 1
    for j1 in j1_list:
        for c in c_list:
            for a1 in a1_list:
                for a7 in a7_list:
                    print(f"\n【配置 #{config_index}】")
                    config = {
                        version: {
                            "j1": j1,
                            "c": c,
                            "a1": a1,
                            "a7": a7
                        }
                    }
                    print(json.dumps(config, indent=4, ensure_ascii=False))
                    config_index += 1
    
    print()
    print("=" * 60)
    print(f"总计 {config_index - 1} 个配置组合")
    print("=" * 60)

def main():
    if len(sys.argv) < 3:
        print("Usage: python analyze_hook_all.py <decompiled_dir> <version>")
        print()
        print("Example:")
        print("  python analyze_hook_all.py decompiled/8.0.76 8.0.76")
        sys.exit(1)
    
    decompiled_dir = sys.argv[1]
    version = sys.argv[2]
    
    print(f"\n分析目录: {decompiled_dir}")
    print(f"版本: {version}")
    print()
    
    candidates = extract_all_candidates(decompiled_dir)
    
    if candidates:
        print_config_combinations(candidates, version)
        
        # 保存到文件
        output_file = Path(decompiled_dir) / f"hook_candidates_{version}.json"
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(candidates, f, indent=2, ensure_ascii=False)
        print(f"\n✅ 候选列表已保存到: {output_file}")

if __name__ == "__main__":
    main()