---
name: compile-verify
description: 指导Agent编译生成的测试代码，验证语法正确性
license: MIT
compatibility: opencode
metadata:
  category: verification
  language: java
  step: 3
---

# 编译验证指导

## 何时使用

当生成了测试代码后，需要验证代码是否能正确编译。

## 验证目标

确保生成的测试代码语法正确，能够编译通过。

## 执行步骤

### 1. 写入测试文件

使用 `write` 工具将测试代码写入指定路径：

```
write(file_path="{testFilePath}", content="{testCode}")
```

### 2. 执行编译

优先使用项目构建工具：

**Maven项目**：
```bash
mvn compile test-compile -q
```

**Gradle项目**：
```bash
gradle compileTestJava -q
```

**直接编译（备选）**：
```bash
javac -cp "dependencies/*:target/classes" -d target/test-classes {testFilePath}
```

### 3. 解析编译结果

分析编译输出，提取以下信息：
- 是否编译成功
- 错误数量
- 每个错误的：文件名、行号、列号、错误描述

## 常见编译错误类型

| 错误类型 | 示例 | 可能原因 |
|---------|------|---------|
| cannot find symbol | cannot find symbol class User | 缺少import或类名错误 |
| incompatible types | incompatible types: int cannot be converted to String | 类型不匹配 |
| method not found | method getUser in class UserService cannot be applied to given types | 方法签名错误 |
| package does not exist | package com.example does not exist | 包路径错误 |
| annotation not applicable | @Mock not applicable to field | 注解使用错误 |

## 输出格式

将编译结果整理为：

```json
{
  "success": true/false,
  "errorMessage": "总结性错误信息",
  "errorDetails": [
    {
      "line": 10,
      "column": 5,
      "message": "cannot find symbol",
      "suggestion": "可能需要添加import语句"
    }
  ]
}
```

## 超时设置

- 编译超时：60秒
- 如果超时，记录为编译失败
