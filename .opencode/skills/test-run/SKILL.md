---
name: test-run
description: 指导Agent运行生成的单元测试，验证测试用例的正确性
license: MIT
compatibility: opencode
metadata:
  category: verification
  language: java
  framework: junit5
  step: 5
---

# 测试运行指导

## 何时使用

当测试代码编译成功后，运行单元测试验证其正确性。

## 运行目标

执行生成的单元测试，收集测试结果和失败详情。

## 执行步骤

### 1. 执行测试命令

优先使用项目构建工具：

**Maven项目**：
```bash
mvn test -Dtest={TestClassName} -q
```

**Gradle项目**：
```bash
gradle test --tests {TestClassName} -q
```

**直接运行（备选）**：
```bash
java -cp "dependencies/*:target/test-classes:target/classes" \
  org.junit.platform.console.ConsoleLauncher \
  -c {TestClassName}
```

### 2. 解析测试结果

分析测试输出，提取以下信息：
- 测试总数
- 通过数量
- 失败数量
- 跳过数量
- 执行时间

### 3. 提取失败详情

对于失败的测试，记录：
- 测试方法名
- 失败消息
- 堆栈跟踪
- 断言期望值vs实际值

## 常见测试失败类型

| 失败类型 | 示例 | 可能原因 |
|---------|------|---------|
| AssertionFailed | expected:<5> but was:<3> | 断言条件错误 |
| NullPointerException | Cannot invoke... | 空指针访问 |
| MockException | Wanted but not invoked | Mock验证失败 |
| UnexpectedException | Expected no exception | 未预期的异常 |

## 输出格式

将测试结果整理为：

```json
{
  "success": true/false,
  "passed": 5,
  "failed": 1,
  "skipped": 0,
  "total": 6,
  "duration": 1234,
  "output": "测试输出日志摘要",
  "failures": [
    {
      "testMethod": "testGetUserById_NotFound",
      "message": "expected:<404> but was:<200>",
      "stackTrace": "...",
      "type": "AssertionFailed"
    }
  ]
}
```

## 超时设置

- 测试超时：120秒
- 单个测试方法超时：30秒
- 如果超时，记录为测试失败

## 结果判断

- **全部通过**：`success: true`，流程结束
- **部分失败**：`success: false`，进入修复流程
- **运行错误**：`success: false`，记录错误信息
