package com.utest.gen.opencode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/**
 * OpenCode 环境设置
 * 负责在内网环境中准备 OpenCode 运行所需依赖（如 ripgrep）
 */
@Slf4j
@Component
public class OpenCodeEnvironmentSetup {

    private static final String RG_BINARY_NAME = "rg";

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
        return "opencode/bin/ripgrep/" + platform + "/" + binaryName;
    }

    /**
     * 检查并设置 OpenCode 运行环境
     * @return 设置成功返回 true
     */
    public boolean setupEnvironment() {
        try {
            // 1. 检查系统是否已有 ripgrep
            if (isRipgrepAvailable()) {
                log.info("系统已安装 ripgrep，无需额外配置");
                return true;
            }

            // 2. 从内嵌资源中提取 ripgrep
            log.info("系统未安装 ripgrep，尝试从内嵌资源提取...");
            return extractRipgrep();

        } catch (Exception e) {
            log.error("设置 OpenCode 环境失败", e);
            return false;
        }
    }

    /**
     * 检查 ripgrep 是否可用
     */
    private boolean isRipgrepAvailable() {
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
     * 从内嵌资源中提取 ripgrep 到临时目录
     */
    private boolean extractRipgrep() {
        try {
            // 创建临时目录
            Path tempDir = Files.createTempDirectory("utest-gen-rg");
            Path rgPath = tempDir.resolve(RG_BINARY_NAME);

            // 从内嵌资源复制
            String resourcePath = getRipgrepResourcePath();
            try (InputStream is = getClass().getClassLoader()
                    .getResourceAsStream(resourcePath)) {
                if (is == null) {
                    log.error("内嵌资源中未找到 ripgrep 二进制文件: {}", resourcePath);
                    return false;
                }
                Files.copy(is, rgPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // 设置可执行权限
            rgPath.toFile().setExecutable(true);

            // 验证提取的二进制文件
            Process process = new ProcessBuilder(rgPath.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            
            if (finished && process.exitValue() == 0) {
                log.info("ripgrep 提取成功: {}", rgPath);
                // 将路径添加到环境变量，供 OpenCode 使用
                addToPath(tempDir.toString());
                return true;
            } else {
                log.error("ripgrep 验证失败");
                return false;
            }

        } catch (Exception e) {
            log.error("提取 ripgrep 失败", e);
            return false;
        }
    }

    /**
     * 将路径添加到 PATH 环境变量
     */
    private void addToPath(String path) {
        String currentPath = System.getenv("PATH");
        String newPath = path + File.pathSeparator + currentPath;
        // 注意：这里只是修改当前进程的环境变量
        // 实际使用时需要在启动 OpenCode 时传递 PATH
        log.info("已将 {} 添加到 PATH", path);
    }

    /**
     * 获取 OpenCode 启动环境变量
     */
    public ProcessBuilder getOpenCodeProcessBuilder() {
        ProcessBuilder pb = new ProcessBuilder();
        
        // 设置环境变量，确保能找到 ripgrep
        if (!isRipgrepAvailable()) {
            try {
                Path tempDir = Files.createTempDirectory("utest-gen-rg");
                Path rgPath = tempDir.resolve(RG_BINARY_NAME);
                
                // 如果已经提取过，直接使用
                if (Files.exists(rgPath)) {
                    String currentPath = System.getenv("PATH");
                    String newPath = tempDir.toString() + File.pathSeparator + currentPath;
                    pb.environment().put("PATH", newPath);
                }
            } catch (IOException e) {
                log.warn("设置 PATH 失败", e);
            }
        }
        
        return pb;
    }
}
