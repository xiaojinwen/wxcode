#!/usr/bin/env python3
"""
通用 Hook 配置提取工具（增强版）
支持多版本微信，参数化配置

使用方法:
    python analyze_hook_enhanced.py <反编译目录> [版本]
    
示例:
    python analyze_hook_enhanced.py decompiled_8.0.49 8.0.49
    python analyze_hook_enhanced.py decompiled_8.0.76 8.0.76 --verbose
"""

import re
import sys
import json
from pathlib import Path
from typing import Dict, List, Optional

# 版本特定配置
VERSION_CONFIGS = {
    "8.0.49": {
        "a1_suffix": "b2",
        "a7_suffix": "f2",
    },
    "8.0.62": {
        "a1_suffix": "h2",
        "a7_suffix": "l2",
    },
    "8.0.70": {
        "a1_suffix": "h2",
        "a7_suffix": "l2",
    },
    "8.0.74": {
        "a1_suffix": "h2",
        "a7_suffix": "l2",
    },
    "8.0.76": {
        "a1_suffix": "i2",
        "a7_suffix": "m2",
    },
}

class HookConfigExtractor:
    def __init__(self, decompiled_dir: str, version: str, verbose: bool = False):
        self.decompiled_dir = Path(decompiled_dir)
        self.version = version
        self.verbose = verbose
        self.version_config = VERSION_CONFIGS.get(version, {})
        
    def log(self, message: str, level: str = "INFO"):
        """打印日志"""
        if self.verbose or level != "DEBUG":
            prefix = {
                "INFO": "ℹ️ ",
                "WARN": "⚠️ ",
                "ERROR": "❌",
                "DEBUG": "🔍",
                "SUCCESS": "✅"
            }.get(level, "")
            print(f"{prefix} {message}")
    
    def find_login_task_file(self) -> Optional[Path]:
        """查找 LoginTask 文件"""
        possible_paths = [
            "sources/com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiLogin$LoginTask.java",
            "sources/com/tencent/mm/plugin/appbrand/jsapi/auth/JsApiAuthorize$AuthorizeTask.java",
        ]
        
        for path in possible_paths:
            full_path = self.decompiled_dir / path
            if full_path.exists():
                self.log(f"找到核心文件: {path}", "SUCCESS")
                return full_path
        
        return None
    
    def extract_with_multiple_patterns(self, content: str, patterns: List[str], name: str) -> Optional[str]:
        """使用多个正则模式尝试提取"""
        for pattern in patterns:
            match = re.search(pattern, content)
            if match:
                result = match.group(1) if match.lastindex else match.group(0)
                self.log(f"使用模式提取 {name}: {result}", "DEBUG")
                return result
        return None
    
    def extract_j1(self, content: str) -> Optional[str]:
        """提取 j1 类"""
        patterns = [
            r'(\w+)\.(\w+)\.d\(\)\.f\(',  # u70.k1.d().f(
            r'(\w+)\.(\w+)\.getInstance\(\)\.submit\(',  # 替代模式
            r'(\w+)\.(\w+)\.get\(\)\.execute\(',  # 替代模式
            r'(\w+)\.(\w+)\.[a-z]+\(\)\.[a-z]+\(',  # 通用模式
        ]
        
        for pattern in patterns:
            match = re.search(pattern, content)
            if match:
                result = f"{match.group(1)}.{match.group(2)}"
                self.log(f"提取 j1: {result}", "SUCCESS")
                return result
        
        self.log("未找到 j1", "WARN")
        return None
    
    def extract_c(self, content: str) -> Optional[str]:
        """提取 c 类"""
        patterns = [
            r'(\w+)\.(\w+)\s+\w+\s*=\s*new\s+\1\.\2\(',  # o60.c cVar = new o60.c(
            r'new\s+(\w+)\.(\w+)\([^)]*LinkedList',  # new XX.YY(..., LinkedList, ...)
        ]
        
        for pattern in patterns:
            match = re.search(pattern, content)
            if match:
                result = f"{match.group(1)}.{match.group(2)}"
                self.log(f"提取 c: {result}", "SUCCESS")
                return result
        
        self.log("未找到 c", "WARN")
        return None
    
    def extract_a1(self, content: str) -> Optional[str]:
        """提取 a1 类"""
        # 尝试从版本配置获取期望的类名
        expected_suffix = self.version_config.get("a1_suffix")
        
        # 模式：b2 b2Var = new b2(this)
        patterns = [
            r'(\w+)\s+\w+\s*=\s*new\s+\1\(this\)',
        ]
        
        for pattern in patterns:
            matches = re.findall(pattern, content)
            if matches:
                # 如果有期望的类名，优先匹配
                if expected_suffix and expected_suffix in matches:
                    result = f"com.tencent.mm.plugin.appbrand.jsapi.auth.{expected_suffix}"
                    self.log(f"提取 a1 (版本配置): {result}", "SUCCESS")
                    return result
                # 否则使用第一个匹配
                result = f"com.tencent.mm.plugin.appbrand.jsapi.auth.{matches[0]}"
                self.log(f"提取 a1: {result}", "SUCCESS")
                return result
        
        self.log("未找到 a1", "WARN")
        return None
    
    def extract_a7(self, content: str) -> Optional[str]:
        """提取 a7 类"""
        expected_suffix = self.version_config.get("a7_suffix")
        
        # 模式：f2 f2Var = new f2(this,
        patterns = [
            r'(\w+)\s+\w+\s*=\s*new\s+\1\(this,',
        ]
        
        for pattern in patterns:
            matches = re.findall(pattern, content)
            if matches:
                if expected_suffix and expected_suffix in matches:
                    result = f"com.tencent.mm.plugin.appbrand.jsapi.auth.{expected_suffix}"
                    self.log(f"提取 a7 (版本配置): {result}", "SUCCESS")
                    return result
                result = f"com.tencent.mm.plugin.appbrand.jsapi.auth.{matches[0]}"
                self.log(f"提取 a7: {result}", "SUCCESS")
                return result
        
        self.log("未找到 a7", "WARN")
        return None
    
    def extract(self) -> Dict[str, Optional[str]]:
        """提取所有配置"""
        self.log(f"开始分析版本: {self.version}")
        self.log(f"反编译目录: {self.decompiled_dir}")
        
        # 查找核心文件
        login_task_file = self.find_login_task_file()
        if not login_task_file:
            self.log("核心文件未找到，请检查反编译结果", "ERROR")
            return {}
        
        content = login_task_file.read_text(encoding='utf-8')
        self.log(f"读取文件: {len(content)} 字符")
        
        return {
            "j1": self.extract_j1(content),
            "c": self.extract_c(content),
            "a1": self.extract_a1(content),
            "a7": self.extract_a7(content),
        }
    
    def print_result(self, config: Dict[str, Optional[str]]):
        """打印结果"""
        print("\n" + "="*60)
        print("✅ 配置提取结果")
        print("="*60)
        
        # JSON 格式
        print("\n【JSON 格式】")
        print(json.dumps({self.version: config}, indent=4, ensure_ascii=False))
        
        # Java 格式
        print("\n【Java 格式】")
        print(f'"{self.version}": {{')
        for key, value in config.items():
            print(f'    "{key}": "{value or "未找到"}",')
        print('}')
        
        # 完整性检查
        missing = [k for k, v in config.items() if not v]
        if missing:
            print(f"\n⚠️  警告: 以下配置项未找到: {', '.join(missing)}")
            print("\n📝 建议手动分析:")
            print(f"   1. 打开: {self.decompiled_dir}/sources/com/tencent/mm/plugin/appbrand/jsapi/auth/")
            print("   2. 查看: JsApiLogin$LoginTask.java")
            print("   3. 搜索: .d().f(  // j1")
            print("   4. 搜索: new XX.c(  // c")
            print("   5. 搜索: new XX(this)  // a1")
            print("   6. 搜索: new XX(this,  // a7")
        else:
            print("\n✓ 全部配置项已提取")

def main():
    import argparse
    
    parser = argparse.ArgumentParser(description="微信 Hook 配置提取工具")
    parser.add_argument("decompiled_dir", help="反编译结果目录")
    parser.add_argument("version", nargs="?", default="unknown", help="微信版本号")
    parser.add_argument("--verbose", "-v", action="store_true", help="详细输出")
    
    args = parser.parse_args()
    
    extractor = HookConfigExtractor(args.decompiled_dir, args.version, args.verbose)
    config = extractor.extract()
    
    if config:
        extractor.print_result(config)
    else:
        print("❌ 提取失败")
        sys.exit(1)

if __name__ == "__main__":
    main()