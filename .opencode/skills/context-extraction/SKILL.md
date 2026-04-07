---
name: context-extraction
description: 指导Agent使用LSP工具提取Java类的完整上下文信息，包括类结构、方法签名、字段、调用关系等
license: MIT
compatibility: opencode
metadata:
  category: analysis
  language: java
  step: 1
---

# 上下文提取指导

## 何时使用

当需要为Java类生成单元测试时，首先需要提取该类的完整上下文信息。

## 提取目标

提取以下信息并整理成结构化数据：

### 类级别信息
- packageName: 包名
- className: 类名
- classAnnotations: 类上的注解（如@Service, @Component等）
- fields: 类字段列表（类型、名称、修饰符）
- constructors: 构造函数列表

### 方法级别信息（针对每个待测方法）
- methodName: 方法名
- signature: 完整方法签名
- parameters: 参数列表（类型、名称、注解）
- returnType: 返回类型
- thrownExceptions: 抛出的异常类型
- methodBody: 方法体源码
- callees: 调用的方法列表（类名.方法名）
- referencedFields: 引用的字段列表

## 提取方法

使用以下方式获取信息：

1. **读取源文件**：使用 `read` 工具读取Java源文件内容
2. **LSP分析**：如果LSP可用，使用LSP接口：
   - `textDocument/documentSymbol` - 获取类结构
   - `textDocument/hover` - 获取方法详情
   - `textDocument/definition` - 获取调用关系
3. **AST解析**：分析源码AST获取调用关系和字段引用

## 输出格式

将提取的信息整理为以下JSON结构：

```json
{
  "classContext": {
    "packageName": "com.example",
    "className": "UserService",
    "classAnnotations": ["@Service"],
    "fields": [
      {"type": "UserRepository", "name": "userRepository", "modifiers": "private final"}
    ]
  },
  "methodContexts": [
    {
      "methodName": "getUserById",
      "signature": "public User getUserById(Long id)",
      "parameters": [{"type": "Long", "name": "id"}],
      "returnType": "User",
      "thrownExceptions": ["UserNotFoundException"],
      "callees": ["UserRepository.findById", "Optional.orElseThrow"],
      "referencedFields": ["userRepository"]
    }
  ]
}
```

## 注意事项

- 确保提取的方法与请求的方法名列表一致
- 对于私有方法调用，需要分析方法体源码
- 记录所有外部依赖（需要Mock的对象）
