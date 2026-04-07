package com.utest.gen.opencode;

import com.utest.gen.config.LlmProperties;
import com.utest.gen.config.OpenCodeProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;
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

    private Process openCodeProcess;
    private volatile boolean running = false;

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
            // 生成 OpenCode 配置文件
            generateConfigFile();

            String serverPath = getServerPath();
            log.info("启动 OpenCode Server: {}", serverPath);

            ProcessBuilder pb = new ProcessBuilder(
                    serverPath, "serve", "--port", String.valueOf(properties.getPort())
            );
            pb.redirectErrorStream(true);
            pb.directory(new File(System.getProperty("user.dir")));

            openCodeProcess = pb.start();

            // 启动日志读取线程
            startLogReader(openCodeProcess);

            // 等待服务就绪
            boolean ready = waitForReady();
            if (ready) {
                running = true;
                log.info("OpenCode Server 启动成功，端口: {}", properties.getPort());
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
     * 生成 OpenCode 配置文件
     * 配置自建的 test-gen-agent，使用外部 prompt 文件
     */
    private void generateConfigFile() throws IOException {
        Path configPath = Paths.get(System.getProperty("user.dir"), ".opencode.json");
        
        // 确保 Agent 配置文件存在
        Path agentConfigPath = Paths.get(System.getProperty("user.dir"), ".opencode", "agents", "test-gen-agent.md");
        if (!Files.exists(agentConfigPath)) {
            log.warn("Agent 配置文件不存在: {}，将使用内联配置", agentConfigPath);
        }

        String configContent = String.format("""
                {
                  "$schema": "https://opencode.ai/config.json",
                  "model": "custom/%s",
                  "providers": {
                    "custom": {
                      "apiKey": "%s",
                      "baseUrl": "%s"
                    }
                  },
                  "lsp": {
                    "java": {
                      "command": "jdtls"
                    }
                  },
                  "agent": {
                    "test-gen-agent": {
                      "description": "AI单元测试生成专用代理，能够自主完成上下文提取、测试代码生成、编译验证和自动修复的完整流程",
                      "model": "custom/%s",
                      "prompt": "file:.opencode/agents/test-gen-agent.md",
                      "tools": {
                        "skill": true,
                        "bash": true,
                        "write": true,
                        "read": true
                      }
                    }
                  }
                }
                """,
                llmProperties.getModel(),
                llmProperties.getApiKey() != null ? llmProperties.getApiKey() : "",
                llmProperties.getApiUrl(),
                llmProperties.getModel()
        );

        Files.writeString(configPath, configContent);
        log.info("已生成 OpenCode 配置文件: {}", configPath);
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
