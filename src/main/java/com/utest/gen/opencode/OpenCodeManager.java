package com.utest.gen.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.utest.gen.config.DbMcpProperties;
import com.utest.gen.config.LlmProperties;
import com.utest.gen.config.OpenCodeProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.ContentType;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OpenCode Server 进程管理器
 * 负责 OpenCode Server 的启动、停止和健康检查
 */
@Slf4j
@Component
public class OpenCodeManager {

    @Autowired
    private OpenCodeProperties properties;

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private OpenCodeEnvironmentSetup environmentSetup;

    @Autowired(required = false)
    private DbMcpProperties dbMcpProperties;

    @org.springframework.beans.factory.annotation.Value("${server.port:8080}")
    private int serverPort;

    private Process openCodeProcess;
    private volatile boolean running = false;

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * 应用启动后自动启动 OpenCode Server
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (properties.isEnabled()) {
            start();
        }
    }

    /**
     * 启动 OpenCode Server
     */
    public synchronized void start() {
        if (running) {
            log.info("OpenCode Server 已经在运行中");
            return;
        }

        try {
            // 1. 设置 OpenCode 运行环境（检查/提取 ripgrep）
            boolean envReady = environmentSetup.setupEnvironment();
            if (!envReady) {
                log.warn("OpenCode 环境设置可能不完整，继续尝试启动...");
            }

            // 2. 生成 OpenCode 配置文件
            generateConfigFile();

            String serverPath = getServerPath();
            log.info("启动 OpenCode Server: {}", serverPath);

            String projectRoot = properties.getProjectRoot();
            if (projectRoot == null || projectRoot.isEmpty()) {
                projectRoot = System.getProperty("user.dir");
            }
            log.info("OpenCode 工作目录: {}", projectRoot);

            // 3. 启动命令：直接使用 serve，不传项目路径参数
            ProcessBuilder pb = new ProcessBuilder(
                    serverPath, "serve", "--port", String.valueOf(properties.getPort())
            );
            pb.redirectErrorStream(true);
            pb.directory(new File(projectRoot));

            // 4. 设置环境变量，确保能找到 ripgrep
            setupRipgrepPath(pb);

            openCodeProcess = pb.start();

            // 启动日志读取线程
            startLogReader(openCodeProcess);

            // 等待服务就绪
            boolean ready = waitForReady();
            if (ready) {
                running = true;
                log.info("OpenCode Server 启动成功，端口: {}", properties.getPort());

                // OpenCode 就绪后，自动执行 bootstrap 任务配置数据源
                if (dbMcpProperties != null && dbMcpProperties.isEnabled()) {
                    bootstrapDatasource();
                }
            } else {
                throw new RuntimeException("OpenCode Server 启动超时");
            }

        } catch (Exception e) {
            log.error("OpenCode Server 启动失败", e);
            throw new RuntimeException("OpenCode Server 启动失败: " + e.getMessage(), e);
        }
    }

    /**
     * 停止 OpenCode Server
     */
    @PreDestroy
    public synchronized void stop() {
        if (openCodeProcess != null && openCodeProcess.isAlive()) {
            log.info("正在停止 OpenCode Server...");
            openCodeProcess.destroy();
            try {
                if (!openCodeProcess.waitFor(10, TimeUnit.SECONDS)) {
                    openCodeProcess.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                openCodeProcess.destroyForcibly();
            }
            running = false;
            log.info("OpenCode Server 已停止");
        }
    }

    /**
     * 获取 OpenCode Server URL
     */
    public String getServerUrl() {
        return "http://127.0.0.1:" + properties.getPort();
    }

    /**
     * 生成 OpenCode 配置文件（兼容 1.4.0：不使用 lsp/agent 等顶层字段）
     * <p>
     * 参考文档：
     * - 提供商：npm/name/options(models)/models/limit 为官方支持字段
     * - 顶层 lsp 在 1.4.0 会被校验拦截，暂不使用
     */
    private void generateConfigFile() throws IOException {
        String projectRoot = properties.getProjectRoot();
        if (projectRoot == null || projectRoot.isEmpty()) {
            projectRoot = System.getProperty("user.dir");
        }

        Path configPath = Paths.get(projectRoot, "opencode.json");
        if (Files.exists(configPath)) {
            Files.delete(configPath);
            log.info("已删除旧的 OpenCode 配置文件: {}", configPath);
        }

        // 路径仅做提示，不阻塞启动
        Path agentConfigPath = Paths.get(projectRoot, ".opencode", "agents", "test-gen-agent.md");
        if (!Files.exists(agentConfigPath)) {
            log.warn("Agent 配置文件不存在: {}，将使用内联配置", agentConfigPath);
        }

        // 1) 基础参数
        String providerId = llmProperties.getProviderId() != null
                ? llmProperties.getProviderId() : "custom";

        String modelId = llmProperties.getModel();           // 例如 glm-4-flash
        String baseURL = llmProperties.getApiUrl();          // 例如 https://open.bigmodel.cn/api/paas/v4
        String apiKey = llmProperties.getApiKey() != null ? llmProperties.getApiKey() : "";

        Integer context = llmProperties.getContext();        // 128000 / 200000
        Integer output = llmProperties.getOutput();         // 4096

        // 2) 固定模板（字段全部来自官方文档示例：npm/name/options/models/limit）
        String template = """
                          {
                            "$schema": "https://opencode.ai/config.json",
                
                            "model": "%s/%s",
                
                            "provider": {
                              "%s": {
                                "npm": "@ai-sdk/openai-compatible",
                                "name": "Custom Provider",
                                "options": {
                                  "baseURL": "%s",
                                  "apiKey": "%s"
                                },
                                "models": {
                                  "%s": {
                                    "name": "%s",
                                    "limit": {
                                      "context": %d,
                                      "output": %d
                                    }
                                  }
                                }
                              }
                            },
                             "permission": {
                              "*": "allow",
                              "read": "allow",
                              "edit": "allow",
                              "bash": "allow"
                            }
                          }
                """.formatted(
                providerId,
                modelId,

                providerId,
                baseURL,
                apiKey,

                modelId,
                modelId,

                context != null ? context : 128000,
                output != null ? output : 8192
        );

        // 3) 用 Jackson 保证 JSON 合法（防止以后手误改坏模板）
        ObjectNode root = (ObjectNode) OM.readTree(template);

        // 4) 如果 DB Agent 已启用，注入 mcpServers 配置
        if (dbMcpProperties != null && dbMcpProperties.isEnabled()) {
            injectMcpServersConfig(root);
        }

        // 5) 写文件（用 Jackson 的 pretty printer，格式干净）
        Files.writeString(configPath, OM.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        log.info("已生成 OpenCode 配置文件: {}", configPath);
    }

    /**
     * 在 opencode.json 中注入 MCP 配置，供 OpenCode 连接本地 DB MCP Server。
     * <p>
     * 使用 remote 类型直接连接 Streamable HTTP 端点 /mcp，禁用 OAuth 自动检测。
     * 配置格式参考：https://opencode.ai/docs/zh-cn/mcp-servers/
     * <pre>
     * {
     *   "mcp": {
     *     "db-mcp-server": {
     *       "type": "remote",
     *       "url": "http://127.0.0.1:8080/mcp",
     *       "enabled": true,
     *       "oauth": false
     *     }
     *   }
     * }
     * </pre>
     */
    private void injectMcpServersConfig(ObjectNode root) {
        ObjectNode mcp = OM.createObjectNode();

        ObjectNode dbMcp = OM.createObjectNode();
        dbMcp.put("type", "remote");
        String mcpUrl = "http://127.0.0.1:" + serverPort + "/mcp";
        dbMcp.put("url", mcpUrl);
        dbMcp.put("enabled", true);
        // 禁用 OAuth 自动检测，本地服务器无需认证
        dbMcp.put("oauth", false);

        mcp.set("db-mcp-server", dbMcp);
        root.set("mcp", mcp);

        log.info("opencode.json 已注入 mcp 配置: db-mcp-server -> {} (remote/Streamable HTTP)", mcpUrl);
    }


    /**
     * 检查服务是否运行中
     */
    public boolean isRunning() {
        return running && openCodeProcess != null && openCodeProcess.isAlive();
    }

    /**
     * 健康检查
     */
    public boolean healthCheck() {
        try {
            URL url = new URL(getServerUrl() + "/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 更新 OpenCode 默认模型
     * 通过 PATCH /config 接口修改配置中的 model 字段
     *
     * @param model 模型名称，如 "custom/gpt-4"
     */
    public void updateDefaultModel(String model) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/config";
            HttpPatch patch = new HttpPatch(url);
            patch.setEntity(new StringEntity(String.format("{\"model\":\"%s\"}", model), ContentType.APPLICATION_JSON));

            client.execute(patch, response -> {
                int code = response.getCode();
                if (code == 200) {
                    log.info("已更新 OpenCode 默认模型: {}", model);
                } else {
                    log.warn("更新 OpenCode 默认模型返回非预期状态码: {}", code);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("更新 OpenCode 默认模型失败: {}", model, e);
        }
    }

    /**
     * 启用自定义 Agent
     * 通过 PATCH /config 接口将自定义 Agent 添加到配置中
     *
     * @param agentName   Agent 名称，如 "test-gen-agent"
     * @param description Agent 描述
     * @param model       使用的模型，如 "custom/gpt-4"
     * @param promptPath  Prompt 文件路径，如 "file:.opencode/agents/test-gen-agent.md"
     * @param tools       启用的工具列表
     */
    public void enableCustomAgent(String agentName, String description, String model,
                                  String promptPath, String[] tools) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/config";
            HttpPatch patch = new HttpPatch(url);

            StringBuilder toolsJson = new StringBuilder();
            if (tools != null && tools.length > 0) {
                toolsJson.append("\"tools\":{");
                for (int i = 0; i < tools.length; i++) {
                    if (i > 0) toolsJson.append(",");
                    toolsJson.append("\"").append(tools[i]).append("\":true");
                }
                toolsJson.append("}");
            }

            String body = String.format(
                    "{\"agent\":{\"%s\":{\"description\":\"%s\",\"model\":\"%s\",\"prompt\":\"%s\",%s}}}",
                    agentName, description, model, promptPath, toolsJson
            );
            patch.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

            client.execute(patch, response -> {
                int code = response.getCode();
                if (code == 200) {
                    log.info("已启用自定义 Agent: {}", agentName);
                } else {
                    log.warn("启用自定义 Agent 返回非预期状态码: {}", code);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("启用自定义 Agent 失败: {}", agentName, e);
        }
    }

    /**
     * 创建新会话
     *
     * @param parentID 父会话ID（可选）
     * @param title    会话标题（可选）
     * @return 会话ID
     */
    public String createSession(String parentID, String title) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/session";
            HttpPost post = new HttpPost(url);

            StringBuilder body = new StringBuilder("{");
            boolean first = true;
            if (parentID != null && !parentID.isEmpty()) {
                body.append("\"parentID\":\"").append(parentID).append("\"");
                first = false;
            }
            if (title != null && !title.isEmpty()) {
                if (!first) body.append(",");
                body.append("\"title\":\"").append(title).append("\"");
            }
            body.append("}");

            post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));

            return client.execute(post, response -> {
                int code = response.getCode();
                if (code == 200 || code == 201) {
                    String respStr = EntityUtils.toString(response.getEntity());
                    String sessionId = extractJsonValue(respStr, "id");
                    if (sessionId != null) {
                        log.info("创建会话成功: id={}", sessionId);
                        return sessionId;
                    }
                }
                throw new RuntimeException("创建会话失败，状态码: " + response.getCode());
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("创建会话失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建新会话（无父会话和标题）
     */
    public String createSession() {
        return createSession(null, null);
    }

    /**
     * 获取会话详情
     *
     * @param sessionId 会话ID
     * @return 会话详情 JSON 字符串
     */
    public String getSession(String sessionId) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/session/" + sessionId;
            HttpGet get = new HttpGet(url);
            return client.execute(get, response -> {
                int code = response.getCode();
                if (code == 200) {
                    return EntityUtils.toString(response.getEntity());
                } else if (code == 404) {
                    throw new RuntimeException("会话不存在: " + sessionId);
                } else {
                    throw new RuntimeException("获取会话详情失败，状态码: " + code);
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("获取会话详情失败: " + e.getMessage(), e);
        }
    }

    /**
     * 列出所有会话
     *
     * @return 会话列表 JSON 字符串
     */
    public String listSessions() {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/session";
            HttpGet get = new HttpGet(url);
            return client.execute(get, response -> {
                int code = response.getCode();
                if (code == 200) {
                    return EntityUtils.toString(response.getEntity());
                } else {
                    throw new RuntimeException("列出会话失败，状态码: " + code);
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("列出会话失败: " + e.getMessage(), e);
        }
    }

    /**
     * 发送消息到指定会话并等待响应
     *
     * @param sessionId 会话ID
     * @param message   消息内容
     * @param agent     Agent 名称（可选）
     * @return 响应 JSON 字符串
     */
    public String sendMessage(String sessionId, String message, String agent) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/session/" + sessionId + "/prompt_async";
            HttpPost post = new HttpPost(url);

            String body = buildMessageBody(message, agent);
            post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));

            client.execute(post, response -> {
                int code = response.getCode();
                if (code == 204) {
                    return null;
                }
                throw new RuntimeException("发送消息失败，状态码: " + code);
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("发送消息失败: " + e.getMessage(), e);
        }

        long timeout = 900000;
        long interval = 1000;
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeout) {
            try {
                String statusJson = getSessionStatus(sessionId);
                if (isSessionComplete(statusJson)) {
                    return getSessionMessages(sessionId);
                }
            } catch (Exception e) {
                log.debug("轮询会话状态失败: {}", e.getMessage());
            }
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("轮询被中断", e);
            }
        }
        throw new RuntimeException("等待会话响应超时");
    }

    /**
     * 获取会话状态
     *
     * @param sessionId 会话ID
     * @return 状态 JSON 字符串
     */
    private String getSessionStatus(String sessionId) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/session/status";
            HttpGet get = new HttpGet(url);
            return client.execute(get, response -> {
                int code = response.getCode();
                if (code == 200) {
                    return EntityUtils.toString(response.getEntity());
                } else {
                    throw new RuntimeException("获取会话状态失败，状态码: " + code);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("获取会话状态失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查会话是否完成
     *
     * @param statusJson 状态 JSON
     * @return 是否完成
     */
    private boolean isSessionComplete(String statusJson) {
        try {
            JsonNode root = OM.readTree(statusJson);
            JsonNode sessionStatus = root.get("status");
            if (sessionStatus != null && sessionStatus.has("idle")) {
                return sessionStatus.get("idle").asBoolean(false);
            }
            return false;
        } catch (Exception e) {
            log.debug("解析会话状态失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取会话的所有消息
     *
     * @param sessionId 会话ID
     * @return 消息 JSON 字符串
     */
    private String getSessionMessages(String sessionId) {
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = getServerUrl() + "/session/" + sessionId + "/message";
            HttpGet get = new HttpGet(url);
            return client.execute(get, response -> {
                int code = response.getCode();
                if (code == 200) {
                    return EntityUtils.toString(response.getEntity());
                } else {
                    throw new RuntimeException("获取会话消息失败，状态码: " + code);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("获取会话消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建消息请求体
     * OpenCode API 要求格式: { agent?, model?, noReply?, system?, tools?, parts }
     */
    private String buildMessageBody(String message, String agent) {
        StringBuilder body = new StringBuilder();
        body.append("{");

        if (agent != null && !agent.isEmpty()) {
            body.append("\"agent\":\"").append(agent).append("\"");
            body.append(",");
        }
        body.append("\"model\":{\"modelID\":\"").append(llmProperties.getModel()).append("\",\"providerID\":\"deepseek\"}");
        body.append(",\"parts\":[{\"type\":\"text\",\"text\":\"").append(escapeJson(message)).append("\"}]}");

        return body.toString();
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int start = json.indexOf(searchKey);
        if (start == -1) return null;
        start = json.indexOf(":", start) + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf("\"", start);
            if (end == -1) return null;
            return json.substring(start, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 获取 OpenCode 可执行文件路径
     */
    private String getServerPath() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch");

        String dirName;
        if (os.contains("mac")) {
            dirName = arch.contains("aarch64") ? "darwin-arm64" : "darwin-x64";
        } else if (os.contains("linux")) {
            dirName = "linux-x64";
        } else if (os.contains("win")) {
            dirName = "win32-x64";
        } else {
            throw new UnsupportedOperationException("不支持的操作系统: " + os);
        }

        String ext = os.contains("win") ? ".exe" : "";
        String binaryName = "opencode" + ext;

        // 预编译二进制路径: opencode/{platform}/opencode
        String relativePath = properties.getBinaryDir() + "/" + dirName + "/" + binaryName;

        // 1. 尝试从外部目录查找（开发环境或手动部署）
        Path externalPath = Paths.get(relativePath);
        if (Files.exists(externalPath)) {
            setExecutable(externalPath);
            return externalPath.toString();
        }

        // 2. 从 classpath 提取到临时目录（JAR 打包后运行）
        Path tempPath = extractBinaryFromJar(relativePath);
        if (tempPath != null) {
            setExecutable(tempPath);
            return tempPath.toString();
        }

        // 3. 尝试从 PATH 环境变量查找
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String pathDir : pathEnv.split(File.pathSeparator)) {
                Path opencodePath = Paths.get(pathDir, binaryName);
                if (Files.exists(opencodePath)) {
                    return opencodePath.toString();
                }
            }
        }

        throw new IOException("找不到 OpenCode 可执行文件，请确保已安装 OpenCode 或将其放入 resources/opencode/ 目录");
    }

    /**
     * 从 JAR 中提取二进制文件到临时目录
     */
    private Path extractBinaryFromJar(String relativePath) throws IOException {
        var resource = getClass().getClassLoader().getResource(relativePath);
        if (resource == null) {
            return null;
        }

        // 判断是否在 JAR 中运行
        String protocol = resource.getProtocol();
        if (!"jar".equals(protocol)) {
            // 开发环境，直接使用文件系统路径
            try {
                return Paths.get(resource.toURI());
            } catch (Exception e) {
                log.warn("无法转换资源路径: {}", e.getMessage());
                return null;
            }
        }

        // JAR 环境，提取到临时目录
        Path tempBase = Paths.get(System.getProperty("java.io.tmpdir"), "utest-gen-opencode");
        String fileName = Paths.get(relativePath).getFileName().toString();
        Path targetPath = tempBase.resolve(fileName);

        // 如果已存在，直接返回
        if (Files.exists(targetPath)) {
            log.info("使用已缓存的 OpenCode: {}", targetPath);
            return targetPath;
        }

        // 创建目录并提取文件
        Files.createDirectories(tempBase);
        try (var in = resource.openStream()) {
            Files.copy(in, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("已将 OpenCode 提取到临时目录: {}", targetPath);
            return targetPath;
        } catch (Exception e) {
            log.warn("从 JAR 提取 OpenCode 失败: {}", e.getMessage());
            // 删除可能已创建的文件
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {
            }
            return null;
        }
    }

    /**
     * 设置可执行权限（Unix 系统）
     */
    private void setExecutable(Path path) throws IOException {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            Set<PosixFilePermission> perms = new HashSet<>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_READ);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_READ);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, perms);
        }
    }

    /**
     * 等待服务就绪
     */
    private boolean waitForReady() {
        int timeout = properties.getStartupTimeout();
        for (int i = 0; i < timeout; i++) {
            if (healthCheck()) {
                return true;
            }
            try {
                Thread.sleep(1000);
                log.debug("等待 OpenCode Server 启动... ({}/{})", i + 1, timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 设置 ripgrep 路径到环境变量
     */
    private void setupRipgrepPath(ProcessBuilder pb) {
        try {
            // 检查系统是否已有 ripgrep
            if (isRipgrepInPath()) {
                log.info("系统 PATH 中已存在 ripgrep");
                return;
            }

            // 根据平台确定资源路径
            String resourcePath = getRipgrepResourcePath();
            String binaryName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);

            // 从内嵌资源中提取 ripgrep
            Path tempDir = Files.createTempDirectory("utest-gen-rg");
            Path rgPath = tempDir.resolve(binaryName);

            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                if (is == null) {
                    log.warn("内嵌资源中未找到 ripgrep: {}", resourcePath);
                    return;
                }
                Files.copy(is, rgPath, StandardCopyOption.REPLACE_EXISTING);

                // 设置可执行权限（非 Windows）
                if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                    rgPath.toFile().setExecutable(true);
                }
                log.info("已提取 ripgrep 到: {}", rgPath);
            }

            // 更新 PATH 环境变量
            Map<String, String> env = pb.environment();
            String currentPath = env.getOrDefault("PATH", System.getenv("PATH"));
            String newPath = tempDir.toString() + File.pathSeparator + currentPath;
            env.put("PATH", newPath);
            log.info("已将 ripgrep 路径添加到 PATH: {}", tempDir);

        } catch (Exception e) {
            log.warn("设置 ripgrep 路径失败: {}", e.getMessage());
        }
    }

    /**
     * 根据平台获取 ripgrep 资源路径
     */
    private String getRipgrepResourcePath() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        String platform;
        if (os.contains("mac") || os.contains("darwin")) {
            platform = arch.contains("aarch64") || arch.contains("arm64")
                    ? "darwin-arm64" : "darwin-x64";
        } else if (os.contains("linux")) {
            platform = arch.contains("aarch64") || arch.contains("arm64")
                    ? "linux-arm64" : "linux-x64";
        } else if (os.contains("win")) {
            platform = "win32-x64";
        } else {
            throw new UnsupportedOperationException("不支持的操作系统: " + os);
        }

        String binaryName = os.contains("win") ? "rg.exe" : "rg";
        return "opencode/bin/ripgrep" + platform + "/" + binaryName;
    }

    /**
     * 检查 ripgrep 是否在 PATH 中
     */
    private boolean isRipgrepInPath() {
        try {
            Process process = new ProcessBuilder("rg", "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Bootstrap 任务：OpenCode 启动后自动读取被测项目配置并配置数据源。
     * <p>
     * 流程：创建临时会话 → 发送 bootstrap 消息 → OpenCode 读取配置文件并调用 configure_datasource MCP 工具。
     * 一次性操作，后续任务通过 health_check 确认数据源已就绪。
     */
    private void bootstrapDatasource() {
        log.info("[Bootstrap] 开始自动配置数据源...");
        try {
            String sessionId = createSession(null, "bootstrap-datasource");

            String bootstrapMessage = """
                    你需要尝试完成一个初始化任务：配置数据库连接。
                    
                    步骤：
                    1. 读取当前项目的数据源配置文件，依次查找：
                       - src/main/resources/application.yml
                       - src/main/resources/application.properties
                       - src/main/resources/application-*.yml
                       - src/main/resources/bootstrap.yml
                    2. 在配置中查找 spring.datasource 的 url、username、password 信息
                       - 如遇 ${VAR:default} 占位符，使用冒号后的默认值
                    3. **如果找到了数据源配置**：调用 MCP 工具 configure_datasource 初始化连接
                    4. **如果没有找到数据源配置**（项目不使用数据库）：直接结束，回复"当前项目无数据源配置，跳过初始化"
                    
                    注意：并非所有项目都有数据库，找不到配置是正常情况，不要报错。
                    这是一个简单的初始化任务，不需要生成任何代码。完成后简要说明结果即可。
                    """;

            // 异步执行 bootstrap，不阻塞主启动流程
            Thread bootstrapThread = new Thread(() -> {
                try {
                    String result = sendMessage(sessionId, bootstrapMessage, null);
                    log.info("[Bootstrap] 数据源配置完成");
                    log.debug("[Bootstrap] 响应: {}", result);
                } catch (Exception e) {
                    log.warn("[Bootstrap] 数据源自动配置失败，Agent 可在任务中通过 health_check 检测并手动配置: {}", e.getMessage());
                }
            }, "opencode-bootstrap-datasource");
            bootstrapThread.setDaemon(true);
            bootstrapThread.start();

        } catch (Exception e) {
            log.warn("[Bootstrap] 创建 bootstrap 会话失败: {}", e.getMessage());
        }
    }

    /**
     * 启动日志读取线程
     */
    private void startLogReader(Process process) {
        Thread logThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[OpenCode] {}", line);
                }
            } catch (IOException e) {
                if (running) {
                    log.warn("OpenCode 日志读取异常: {}", e.getMessage());
                }
            }
        }, "opencode-log-reader");
        logThread.setDaemon(true);
        logThread.start();
    }
}
