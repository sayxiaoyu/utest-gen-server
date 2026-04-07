---
name: auto-fix
description: 指导Agent根据编译或测试错误信息，自动修复测试代码中的问题
license: MIT
compatibility: opencode
metadata:
  category: fix
  language: java
  step: 4
---

# 自动修复指导

## 何时使用

当编译失败或测试运行时，使用本指导修复代码中的问题。

## 修复目标

分析错误信息，生成修复后的代码，使编译通过或测试通过。

## 修复策略

### 第1轮修复（精准修复）

针对具体错误进行精准修复：

| 错误类型 | 修复方法 |
|---------|---------|
| cannot find symbol | 添加缺失的import语句，或修正类名/方法名 |
| incompatible types | 修正类型转换，或调整变量类型 |
| method not found | 修正方法调用，检查参数类型和数量 |
| variable not found | 添加变量声明，或修正变量名 |
| package does not exist | 修正包路径，或添加依赖 |
| annotation not applicable | 移除错误注解，或修正注解使用位置 |

### 第2轮修复（深度修复）

如果第1轮修复后仍有错误，进行综合分析：
- 检查代码整体结构是否合理
- 验证Mock对象是否正确设置
- 检查测试数据准备是否完整
- 修复潜在的依赖注入问题
- 检查测试生命周期方法（@BeforeEach等）

## 修复步骤

1. **分析错误**：解析错误信息，确定错误类型和位置
2. **定位代码**：找到需要修改的代码行
3. **生成修复**：根据修复策略修改代码
4. **验证修复**：确保修复不会引入新问题
5. **返回结果**：输出修复后的代码和说明

## 常见修复示例

### 示例1：缺少import
```java
// 修复前：
List<String> list = new ArrayList<>();
// 错误: cannot find symbol List

// 修复后：
import java.util.List;
import java.util.ArrayList;
List<String> list = new ArrayList<>();
```

### 示例2：Mock设置错误
```java
// 修复前：
when(userRepository.findById(1L)).thenReturn(null);
// 错误: 返回类型不匹配

// 修复后：
when(userRepository.findById(1L)).thenReturn(Optional.empty());
```

### 示例3：断言错误
```java
// 修复前：
assertEquals(user, result);
// 错误: 对象引用比较

// 修复后：
assertEquals(user.getId(), result.getId());
assertEquals(user.getName(), result.getName());
```

## 输出格式

```json
{
  "success": true/false,
  "fixedCode": "修复后的完整代码",
  "fixDescription": "修复说明，列出修改点",
  "remainingErrors": "剩余未修复的错误（如有）"
}
```

## 约束

- 最多2轮修复
- 保持原有测试逻辑不变
- 只修复错误，不进行代码重构
- 确保修复后的代码风格一致
