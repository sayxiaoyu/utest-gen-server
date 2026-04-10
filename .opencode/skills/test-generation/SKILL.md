---
name: test-generation
description: 自动化Java单元测试生成技能，集成上下文提取、代码生成、编译验证和自动修复的完整流程
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
推断测试类路径 → 检查是否存在
  ↓
[不存在] → 创建新测试类
[存在]   → 解析已有测试类，识别各方法测试位置
  ↓
LSP提取上下文
  ↓
增量生成测试代码（新方法插入，已有方法覆盖）
  ↓
编译验证
  ↓
[失败] → 自动修复 → 重新编译 (最多2次)
  ↓
运行测试
  ↓
[失败] → 自动修复 → 重新运行 (最多2次)
  ↓
输出: 测试代码 + 执行结果 + 方法级行号信息
```


## 增量更新策略

### 1. 推断测试类文件路径

根据源文件路径，按以下规则推断对应的测试类路径：

```
源文件: src/main/java/com/example/UserService.java
测试文件: src/test/java/com/example/UserServiceTest.java

规则:
1. 将 src/main/java 替换为 src/test/java
2. 在类名后添加 Test 后缀（如 UserService → UserServiceTest）
```

### 2. 检查测试类是否存在

使用 `read` 工具尝试读取推断的测试类文件：
- **文件不存在** → 创建全新的测试类
- **文件存在** → 进入增量更新模式

### 3. 解析已有测试类（增量更新模式）

若测试类已存在，解析其结构以识别各方法的测试位置：

```java
// 示例：已有测试类结构
public class UserServiceTest {
    
    @Test
    void getUserById_existingUser_returnsUser() {  // ← 识别这是 getUserById 的测试
        // ...
    }
    
    @Test
    void getUserById_nonExistingUser_returnsNull() {  // ← 同属 getUserById 的测试
        // ...
    }
    
    // saveUser 的测试不存在，需要插入
}
```

识别规则：
- 测试方法名通常包含被测方法名（如 `getUserById_*`）
- 记录每个被测方法对应的测试方法的起止行号
- 为后续插入/覆盖提供位置信息

### 4. 增量生成策略

对于每个待测方法：

| 场景 | 处理方式 | 行号记录 |
|------|---------|---------|
| 方法已有测试 | 覆盖原有测试方法 | 更新 startLine/endLine |
| 方法无测试 | 在类中合适位置插入新测试 | 记录新的 startLine/endLine |
| 全新测试类 | 生成完整测试类 | 记录所有方法的行号 |


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
  "fixRounds": 0,
  "methodResults": [
    {
      "methodName": "getUserById",
      "startLine": 25,
      "endLine": 55,
      "testFunctions": [
        {
          "functionName": "getUserById_existingUser_returnsUser",
          "lineNumber": 26,
          "scenario": "正常情况：用户存在时返回用户",
          "code": "@Test\nvoid getUserById_existingUser_returnsUser() {...}"
        }
      ]
    }
  ]
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
  "fixHistory": ["修复导入错误", "修复类型不匹配"],
  "methodResults": []
}
```

**methodResults 说明**：
- 记录每个被测方法在测试类中的位置信息
- 用于下次增量更新时定位插入/覆盖位置
- `startLine`/`endLine`：该方法所有测试函数在测试类中的起止行号
