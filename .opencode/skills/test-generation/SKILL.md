---
name: test-generation
description: 自动化Java单元测试生成技能，集成上下文提取、代码生成、编译验证和自动修复的完整流程
license: MIT
compatibility: opencode
metadata:
  category: testing
  language: java
  framework: junit5
---

## 功能概述

本Skill提供完整的Java单元测试自动化生成能力，包括：
- 智能上下文提取（基于LSP）
- AI驱动的测试代码生成
- 自动编译验证
- 智能错误修复（最多2轮）
- 测试运行验证

## 使用场景

1. 为新编写的业务方法生成单元测试
2. 为遗留代码补全测试覆盖
3. 批量生成整个类的测试用例
4. 持续集成中的自动化测试生成

## 工作流

```
输入: 源文件路径 + 方法名列表
  ↓
LSP提取上下文
  ↓
LLM生成测试类
  ↓
编译验证
  ↓
[失败] → 自动修复 → 重新编译 (最多2次)
  ↓
运行测试
  ↓
[失败] → 自动修复 → 重新运行 (最多2次)
  ↓
输出: 测试代码 + 执行结果
```

## 集成方式

### 方式1: 通过Agent调用
```
使用test-gen-agent代理执行测试生成任务
```

### 方式2: 通过API调用
```bash
curl -X POST http://localhost:3000/api/agent/run \
  -H "Content-Type: application/json" \
  -d '{
    "agent": "test-gen-agent",
    "prompt": "为src/main/java/com/example/UserService.java中的getUserById和saveUser方法生成单元测试"
  }'
```

### 方式3: 通过命令调用
```bash
opencode run --agent test-gen-agent "生成UserService类的单元测试"
```

## 配置参数

在 `.opencode.json` 中配置：

```json
{
  "agent": {
    "test-gen-agent": {
      "model": "custom/gpt-4",
      "timeout": 300000,
      "permission": {
        "skill": {
          "test-generation": "allow"
        }
      }
    }
  }
}
```

## 输出规范

成功时返回：
```json
{
  "success": true,
  "testCode": "package com.example...",
  "testFilePath": "src/test/java/com/example/UserServiceTest.java",
  "testClassName": "com.example.UserServiceTest",
  "scenarios": ["testGetUserById_Success", "testGetUserById_NotFound"],
  "compileSuccess": true,
  "testPassed": true,
  "fixRounds": 0
}
```

失败时返回：
```json
{
  "success": false,
  "testCode": "...",
  "compileSuccess": false,
  "errorMessage": "编译错误: cannot find symbol...",
  "fixRounds": 2,
  "fixHistory": ["修复导入错误", "修复类型不匹配"]
}
```
