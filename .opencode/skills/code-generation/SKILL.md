---
name: code-generation
description: 指导Agent基于提取的上下文信息，生成完整的JUnit 5 + Mockito单元测试类代码
license: MIT
compatibility: opencode
metadata:
  category: generation
  language: java
  framework: junit5
  step: 2
---

# 代码生成指导

## 何时使用

当已经提取了类的上下文信息后，使用本指导生成对应的单元测试代码。

## 生成目标

生成一个完整的、可直接编译运行的JUnit 5 + Mockito测试类。

## 生成步骤

### 1. 分析依赖
根据上下文信息，识别需要Mock的依赖：
- 被`@Autowired`或构造函数注入的字段
- 方法中调用的外部服务
- 静态工具类调用

### 2. 构建类结构

```java
@ExtendWith(MockitoExtension.class)
public class {ClassName}Test {
    
    @Mock
    private Dependency dependency;
    
    @InjectMocks
    private {ClassName} target;
    
    @BeforeEach
    void setUp() {
        // 初始化
    }
    
    // 测试方法...
}
```

### 3. 为每个方法生成测试用例

#### 测试方法命名规范
- 正常流程: `{methodName}_Success`
- 边界条件: `{methodName}_EdgeCase_{condition}`
- 异常情况: `{methodName}_Exception_{exceptionType}`

#### 覆盖要求
每个待测方法至少生成3个测试用例：

1. **正常流程测试**
   - 标准输入参数
   - 预期正常返回值
   - 验证Mock调用

2. **边界条件测试**
   - 空值参数（null, empty）
   - 极值（Integer.MAX_VALUE, 空集合等）
   - 边界值（空字符串、长度为0的数组等）

3. **异常情况测试**
   - 抛出预期的异常
   - 验证异常类型和消息
   - 异常发生时的清理逻辑

### 4. Mock设置规范

```java
// 设置Mock返回值
when(dependency.someMethod(any())).thenReturn(expectedValue);

// 设置Mock抛出异常
when(dependency.someMethod(any())).thenThrow(new SomeException());

// 验证Mock调用
verify(dependency, times(1)).someMethod(argCaptor.capture());
```

### 5. 断言规范

```java
// 返回值断言
assertEquals(expected, actual);
assertNotNull(result);

// 异常断言
Exception exception = assertThrows(SomeException.class, () -> {
    target.someMethod(invalidArg);
});
assertEquals("expected message", exception.getMessage());

// 列表断言
assertThat(result).hasSize(2).containsExactlyInAnyOrder(item1, item2);
```

## 输出要求

- 完整的Java类代码
- 所有必要的import语句
- 类名格式：`{OriginalClassName}Test`
- 代码格式符合Google Java Style
