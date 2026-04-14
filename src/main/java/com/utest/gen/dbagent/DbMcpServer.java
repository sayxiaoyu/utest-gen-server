package com.utest.gen.dbagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utest.gen.config.DbMcpProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;

/**
 * DB MCP Server
 * <p>
 * 暴露 MCP Tools 供 OpenCode 自主调用，用于单元测试生成时的真实数据采样。
 * <p>
 * 数据源初始化策略：
 * <ol>
 *   <li>OpenCode 启动后自动执行 bootstrap 任务，读取被测项目配置并调用 configure_datasource</li>
 *   <li>后续任务通过 health_check 确认数据源已就绪，直接采样</li>
 *   <li>configure_datasource 保留作为运行时覆盖/重配的备用手段</li>
 * </ol>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "db-mcp.enabled", havingValue = "true")
public class DbMcpServer {

    private final DbMcpProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 动态初始化的 JdbcTemplate，由 configure_datasource 工具设置 */
    private volatile JdbcTemplate jdbcTemplate;

    /** 当前数据源描述信息（用于日志和状态查询） */
    private volatile String currentDatasourceInfo = "未配置";

    public DbMcpServer(DbMcpProperties properties) {
        this.properties = properties;
        log.info("DB MCP Server 已注册，等待 OpenCode bootstrap 任务配置数据源");
    }

    // ==================== MCP Tools ====================

    /**
     * MCP Tool: 动态配置数据库连接
     * <p>
     * Agent 应在调用其他查询工具之前，先读取被测项目的配置文件（如 application.yml），
     * 提取数据源连接信息后调用此工具完成初始化。
     */
    @Tool(name = "configure_datasource", description = "配置或覆盖数据库连接。如果 health_check 返回 healthy，说明数据源已在 bootstrap 时初始化，无需再次调用。")
    public Map<String, Object> configureDatasource(
            @ToolParam(description = "JDBC URL，如 jdbc:mysql://localhost:3306/mydb") String url,
            @ToolParam(description = "数据库用户名") String username,
            @ToolParam(description = "数据库密码") String password,
            @ToolParam(description = "JDBC驱动类名，如 com.mysql.cj.jdbc.Driver", required = false) String driverClassName) {

        try {
            String driver = (driverClassName != null && !driverClassName.isBlank())
                    ? driverClassName
                    : inferDriverClassName(url);

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(driver);
            dataSource.setUrl(url);
            dataSource.setUsername(username);
            dataSource.setPassword(password);

            JdbcTemplate jt = new JdbcTemplate(dataSource);
            jt.setQueryTimeout(properties.getQueryLimit().getTimeoutSeconds());

            // 验证连接
            jt.queryForObject("SELECT 1", Integer.class);

            this.jdbcTemplate = jt;
            this.currentDatasourceInfo = maskUrl(url);

            log.info("数据源配置成功: url={}, driver={}", maskUrl(url), driver);

            return Map.of(
                    "status", "success",
                    "message", "数据源配置成功，连接验证通过",
                    "url", maskUrl(url),
                    "driver", driver
            );
        } catch (Exception e) {
            log.error("数据源配置失败: url={}", maskUrl(url), e);
            return Map.of(
                    "status", "error",
                    "message", "数据源配置失败: " + e.getMessage(),
                    "url", maskUrl(url)
            );
        }
    }

    /**
     * MCP Tool: 查询单张表的数据样本
     */
    @Tool(name = "data_query", description = "查询数据库表的数据样本，返回脱敏后的结果。用于单元测试生成时获取真实业务数据。需先调用 configure_datasource 配置数据源。")
    public Map<String, Object> queryData(
            @ToolParam(description = "目标表名") String table,
            @ToolParam(description = "返回条数限制，默认10", required = false) Integer limit,
            @ToolParam(description = "查询列名列表（逗号分隔），为空则查询全部", required = false) String columns,
            @ToolParam(description = "是否随机采样", required = false) Boolean randomSample) {

        Map<String, Object> check = ensureDatasourceConfigured();
        if (check != null) return check;

        int maxRows = (limit != null && limit > 0)
                ? Math.min(limit, properties.getQueryLimit().getMaxRows())
                : properties.getQueryLimit().getMaxRows();

        try {
            // 安全校验
            validateTableName(table);

            // 构造 SQL
            String columnClause = (columns != null && !columns.isBlank())
                    ? columns : "*";
            String sql = "SELECT " + columnClause + " FROM " + table;

            // 随机采样
            if (Boolean.TRUE.equals(randomSample)) {
                sql += " ORDER BY RAND()";
            }
            sql += " LIMIT " + maxRows;

            log.info("MCP data_query: table={}, sql={}", table, sql);

            List<Map<String, Object>> results = executeQuery(sql);

            return Map.of(
                    "status", "success",
                    "table", table,
                    "rowCount", results.size(),
                    "samples", results
            );

        } catch (Exception e) {
            log.error("data_query 失败: table={}", table, e);
            return Map.of(
                    "status", "error",
                    "table", table,
                    "error", Map.of("code", "QUERY_FAILED", "message", e.getMessage())
            );
        }
    }

    /**
     * MCP Tool: 批量查询多张表的数据样本
     */
    @Tool(name = "data_query_batch", description = "批量查询同一数据库中多张表的数据样本，一次调用返回所有表的结果。需先调用 configure_datasource 配置数据源。")
    public Map<String, Object> queryDataBatch(
            @ToolParam(description = "要查询的表名列表（逗号分隔）") String tables,
            @ToolParam(description = "每张表返回条数限制，默认10", required = false) Integer limit,
            @ToolParam(description = "是否随机采样", required = false) Boolean randomSample) {

        Map<String, Object> check = ensureDatasourceConfigured();
        if (check != null) return check;

        if (tables == null || tables.isBlank()) {
            return Map.of("status", "error",
                    "error", Map.of("code", "EMPTY_TABLES", "message", "表名列表不能为空"));
        }

        int maxRows = (limit != null && limit > 0)
                ? Math.min(limit, properties.getQueryLimit().getMaxRows())
                : properties.getQueryLimit().getMaxRows();

        List<String> tableList = Arrays.stream(tables.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        log.info("MCP data_query_batch: tables={}", tableList);

        Map<String, Object> results = new LinkedHashMap<>();
        for (String table : tableList) {
            try {
                validateTableName(table);

                String sql = "SELECT * FROM " + table;
                if (Boolean.TRUE.equals(randomSample)) {
                    sql += " ORDER BY RAND()";
                }
                sql += " LIMIT " + maxRows;

                List<Map<String, Object>> rows = executeQuery(sql);
                results.put(table, Map.of(
                        "status", "success",
                        "rowCount", rows.size(),
                        "samples", rows
                ));
            } catch (Exception e) {
                log.warn("batch query 单表失败: table={}, {}", table, e.getMessage());
                results.put(table, Map.of(
                        "status", "error",
                        "error", Map.of("message", e.getMessage())
                ));
            }
        }

        return Map.of(
                "status", "success",
                "results", results,
                "tablesQueried", tableList.size()
        );
    }

    /**
     * MCP Tool: 列出数据库中所有表名
     */
    @Tool(name = "tables_list", description = "列出数据库中所有用户表的表名，可用于判断表是否存在。需先调用 configure_datasource 配置数据源。")
    public Map<String, Object> listTables() {
        Map<String, Object> check = ensureDatasourceConfigured();
        if (check != null) return check;

        log.info("MCP tables_list");

        try {
            List<String> tableNames = new ArrayList<>();
            DataSource ds = jdbcTemplate.getDataSource();
            if (ds != null) {
                try (var conn = ds.getConnection()) {
                    DatabaseMetaData meta = conn.getMetaData();
                    try (ResultSet rs = meta.getTables(conn.getCatalog(), conn.getSchema(),
                            "%", new String[]{"TABLE"})) {
                        while (rs.next()) {
                            tableNames.add(rs.getString("TABLE_NAME"));
                        }
                    }
                }
            }

            return Map.of(
                    "tables", tableNames,
                    "count", tableNames.size()
            );

        } catch (Exception e) {
            log.error("tables_list 失败", e);
            return Map.of("error", Map.of("message", e.getMessage()), "tables", List.of(), "count", 0);
        }
    }

    /**
     * MCP Tool: 获取表结构信息
     */
    @Tool(name = "schema_get", description = "获取指定表的字段结构信息，包括列名、类型、是否可空等。需先调用 configure_datasource 配置数据源。")
    public Map<String, Object> getSchema(
            @ToolParam(description = "目标表名") String table) {

        Map<String, Object> check = ensureDatasourceConfigured();
        if (check != null) return check;

        log.info("MCP schema_get: table={}", table);

        try {
            validateTableName(table);

            List<Map<String, Object>> columns = new ArrayList<>();
            DataSource ds = jdbcTemplate.getDataSource();
            if (ds != null) {
                try (var conn = ds.getConnection()) {
                    DatabaseMetaData meta = conn.getMetaData();
                    try (ResultSet rs = meta.getColumns(conn.getCatalog(), conn.getSchema(), table, "%")) {
                        while (rs.next()) {
                            columns.add(Map.of(
                                    "name", rs.getString("COLUMN_NAME"),
                                    "type", rs.getString("TYPE_NAME"),
                                    "size", rs.getInt("COLUMN_SIZE"),
                                    "nullable", "YES".equals(rs.getString("IS_NULLABLE")),
                                    "remarks", rs.getString("REMARKS") != null ? rs.getString("REMARKS") : ""
                            ));
                        }
                    }
                }
            }

            return Map.of(
                    "table", table,
                    "columns", columns,
                    "columnCount", columns.size()
            );

        } catch (Exception e) {
            log.error("schema_get 失败: table={}", table, e);
            return Map.of(
                    "table", table,
                    "error", Map.of("message", e.getMessage()),
                    "columns", List.of()
            );
        }
    }

    /**
     * MCP Tool: 健康检查
     */
    @Tool(name = "health_check", description = "检查数据库连接是否正常，返回连接状态和当前数据源信息")
    public Map<String, Object> healthCheck() {
        if (jdbcTemplate == null) {
            return Map.of("status", "not_configured",
                    "message", "数据源尚未配置，请先调用 configure_datasource 工具");
        }
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Map.of("status", "healthy", "database", "connected",
                    "datasource", currentDatasourceInfo);
        } catch (Exception e) {
            return Map.of("status", "unhealthy", "database", "disconnected",
                    "datasource", currentDatasourceInfo, "error", e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 执行 SQL 查询，返回结果列表
     */
    private List<Map<String, Object>> executeQuery(String sql) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String colName = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                // 日期类型转为字符串，避免序列化问题
                if (value instanceof java.sql.Timestamp ts) {
                    value = ts.toLocalDateTime().toString();
                } else if (value instanceof java.sql.Date d) {
                    value = d.toLocalDate().toString();
                } else if (value instanceof java.sql.Time t) {
                    value = t.toLocalTime().toString();
                }
                row.put(colName, value);
            }
            return row;
        });
    }

    /**
     * 检查数据源是否已配置，未配置则返回错误信息
     */
    private Map<String, Object> ensureDatasourceConfigured() {
        if (jdbcTemplate == null) {
            return Map.of(
                    "status", "error",
                    "error", Map.of("code", "DATASOURCE_NOT_CONFIGURED",
                            "message", "数据源尚未配置。请先读取被测项目的配置文件(application.yml/properties)，提取数据库连接信息后调用 configure_datasource 工具。")
            );
        }
        return null;
    }

    /**
     * 根据 JDBC URL 推断驱动类名
     */
    private String inferDriverClassName(String url) {
        if (url == null) return "com.mysql.cj.jdbc.Driver";
        if (url.startsWith("jdbc:mysql:")) return "com.mysql.cj.jdbc.Driver";
        if (url.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
        if (url.startsWith("jdbc:oracle:")) return "oracle.jdbc.OracleDriver";
        if (url.startsWith("jdbc:sqlserver:")) return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        if (url.startsWith("jdbc:h2:")) return "org.h2.Driver";
        return "com.mysql.cj.jdbc.Driver";
    }

    /**
     * 表名安全校验（防止 SQL 注入）
     */
    private void validateTableName(String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        // 只允许字母、数字、下划线、点号
        if (!table.matches("^[a-zA-Z_][a-zA-Z0-9_.]*$")) {
            throw new IllegalArgumentException("非法表名: " + table);
        }
        // 检查是否包含禁止关键字
        String upper = table.toUpperCase();
        for (String keyword : properties.getSecurity().getForbiddenKeywords()) {
            if (upper.contains(keyword)) {
                throw new IllegalArgumentException("表名包含禁止关键字: " + keyword);
            }
        }
    }

    /** 隐藏 URL 中的敏感信息 */
    private String maskUrl(String url) {
        if (url == null) return "null";
        return url.replaceAll("password=[^&;]*", "password=***");
    }
}
