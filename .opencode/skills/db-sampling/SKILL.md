---
name: db-sampling
description: 指导Agent判断被测方法是否涉及数据库操作，若需要则通过MCP工具采样真实数据用于测试生成
license: MIT
compatibility: opencode
metadata:
  category: data
  language: java
  step: 1.5
---

# 数据库真实数据采样指导

## 何时使用

在上下文提取之后、代码生成之前，判断被测方法是否涉及数据库操作。
若涉及，则通过 MCP 工具采样真实业务数据，用于生成更准确的测试用例。

## 判断规则

### 需要采样的情况（满足任一即是）

- 方法调用了 Mapper、Repository、Dao 的任何方法
- 方法调用了含 get/select/query/find/list/load/fetch/save/insert/update/delete 语义的 Service 方法
- 方法使用了 MyBatis-Plus API（QueryWrapper、LambdaQueryWrapper、getOne、list、page 等）
- 方法执行写操作（insert/update/delete/save），写操作测试同样需要真实数据作为前置条件
- 方法参数或返回值涉及 Entity 对象

### 无需采样的情况

- 方法仅做纯逻辑计算、格式转换、字符串处理
- 方法仅操作内存数据结构，不涉及任何持久化调用
- 方法是纯工具类方法（如日期处理、加密、格式化）

## 可用 MCP 工具

当 DB Agent MCP Server 已连接时，以下工具可用：

| 工具名 | 用途 | 参数 |
|--------|------|------|
| `configure_datasource` | **配置数据源（必须首先调用）** | url, username, password, driverClassName(可选) |
| `tables_list` | 列出数据库所有表名 | 无 |
| `schema_get` | 获取表结构（字段名、类型、注释） | table |
| `data_query` | 查询单张表数据样本 | table, limit, columns, randomSample |
| `data_query_batch` | 批量查询多张表 | tables(逗号分隔), limit, randomSample |
| `health_check` | 检查数据库连接状态 | 无 |

## 执行步骤

### 0. 读取被测项目的数据源配置（前置步骤）

**重要**：数据源信息不是预先配置好的，你需要自己从被测项目中获取。

1. **定位配置文件**：在被测项目中查找数据源配置文件：
   - `src/main/resources/application.yml`
   - `src/main/resources/application.properties`
   - `src/main/resources/application-*.yml`（profile 文件）
   - `src/main/resources/bootstrap.yml`

2. **读取并提取连接信息**，常见配置格式：
   ```yaml
   # Spring Boot 标准格式
   spring:
     datasource:
       url: jdbc:mysql://localhost:3306/mydb
       username: root
       password: 123456
       driver-class-name: com.mysql.cj.jdbc.Driver
   ```
   ```properties
   # properties 格式
   spring.datasource.url=jdbc:mysql://localhost:3306/mydb
   spring.datasource.username=root
   spring.datasource.password=123456
   ```
   注意也可能使用环境变量占位符如 `${DB_URL:default_value}`，使用冒号后的默认值即可。

3. **调用 configure_datasource 初始化连接**：
   ```
   调用 configure_datasource(url="jdbc:mysql://...", username="...", password="...")
   → 如果返回 status=success，说明连接成功，可以继续采样
   → 如果返回 status=error，说明连接失败，跳过采样步骤
   ```
   driverClassName 参数通常可以省略，工具会根据 JDBC URL 前缀自动推断。

### 1. 分析被测方法，判断是否需要采样

根据上一步提取的上下文信息：
- 检查方法体中是否有 Mapper/Dao/Repository 调用
- 检查方法参数和返回值类型是否包含 Entity
- 检查是否有 SQL 相关注解（@Select, @Insert 等）

### 2. 识别涉及的数据库表

从方法上下文中识别相关的表名：
- **Entity 类名推断**：`PublishInfo` → `publish_info`（驼峰转下划线）
- **Mapper 名推断**：`publishInfoMapper` → `publish_info`
- **直接 SQL**：从 @Select/@Insert 注解中提取表名
- **不确定时**：先调用 `tables_list` 列出所有表名，再匹配

### 3. 获取表结构（可选）

如果不确定表的字段结构：
```
调用 schema_get(table="表名")
→ 返回字段列表（名称、类型、是否可空、注释）
```

### 4. 采样真实数据

**单表查询**：
```
调用 data_query(table="publish_info", limit=5, randomSample=true)
→ 返回 5 条随机样本数据
```

**多表查询**（推荐，减少调用次数）：
```
调用 data_query_batch(tables="publish_info,order_item,user_info", limit=5, randomSample=true)
→ 一次返回所有表的样本数据
```

### 5. 整理采样数据

将采样数据整理为测试可用的格式：
- 提取有代表性的字段值作为测试数据
- 用于构造 Mock 返回值和断言条件
- 确保数据覆盖正常、边界、异常三类场景

## 输出格式

将采样结果整理后，传递给下一步（代码生成），格式如下：

```json
{
  "dbSamplingRequired": true,
  "tablesQueried": ["publish_info", "order_item"],
  "sampleData": {
    "publish_info": [
      {"id": 1, "publish_name": "示例产品", "status": "ACTIVE", "price": 99.00}
    ],
    "order_item": [
      {"id": 100, "order_id": "ORD001", "product_id": 1, "quantity": 2}
    ]
  }
}
```

若判断无需采样：
```json
{
  "dbSamplingRequired": false,
  "reason": "该方法为纯逻辑操作，无需数据库数据样本"
}
```

## 注意事项

1. **configure_datasource 必须先调用**：在调用任何查询工具之前，必须先完成数据源配置
2. **MCP 工具不可用时**：如果 DB Agent 未启用或连接失败，跳过采样步骤，使用模拟数据生成测试
3. **数据已脱敏**：MCP Server 返回的数据已经过脱敏处理，可直接用于测试
4. **采样数量**：默认每表 5-10 条即可，足够覆盖测试场景
5. **优先使用 `data_query_batch`**：涉及多表时，一次调用比多次单表调用更高效
6. **配置文件中的占位符**：如遇 `${VAR:default}` 格式，取冒号后的默认值；如无默认值且无法确定，跳过采样
