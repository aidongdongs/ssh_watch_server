package com.show.util;

import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * SSH 连接与远程命令执行工具类
 * 基于 JSch 库实现，支持远程命令执行及 CPU 使用率解析
 * 文件传输功能已迁移至 com.show.sftp 包
 */
public class SSHUtil {

    private static final Logger log = LoggerFactory.getLogger(SSHUtil.class);

    private Session session;
    private ChannelExec channel;

    /**
     * 建立 SSH 连接
     */
    public void  connect(String host, int port, String username, String password) throws JSchException {
        if (isConnected()) {
            log.info("SSH 已连接，无需重复连接");
            return;
        }

        JSch jsch = new JSch();
        session = jsch.getSession(username, host, port);
        session.setPassword(password);

        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);

        session.connect(30000); // 30秒超时
        log.info("SSH 连接成功: {}:{}", host, port);
    }

    /**
     * 检查当前 SSH 连接是否有效
     */
    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    /**
     * 执行远程命令
     */
    /**
     * 执行远程命令（增强版）
     *
     * @param command 要执行的命令
     * @param timeoutSeconds 超时时间（秒），默认 30 秒
     * @return 命令输出（stdout + stderr 合并）
     * @throws JSchException, IOException, RuntimeException
     */
    public String executeCommand(String command, int timeoutSeconds) throws JSchException, IOException {
        if (!isConnected()) {
            throw new IllegalStateException("SSH 未连接，请先调用 connect()");
        }

        log.info("开始执行命令: {}", command);

        ChannelExec channel = null;
        InputStream stdout = null;
        InputStream stderr = null;
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            // 分别获取标准输出和错误输出
            stdout = channel.getInputStream();
            stderr = channel.getErrStream(); // 👈 关键：捕获错误流

            channel.connect();

            long startTime = System.currentTimeMillis();
            long timeout = timeoutSeconds * 1000L;

            // 非阻塞读取 stdout 和 stderr
            while (true) {
                // 读取 stdout
                while (stdout.available() > 0) {
                    byte[] tmp = new byte[1024];
                    int i = stdout.read(tmp, 0, Math.min(tmp.length, stdout.available()));
                    if (i > 0) {
                        output.append(new String(tmp, 0, i, StandardCharsets.UTF_8));
                    }
                }

                // 读取 stderr
                while (stderr.available() > 0) {
                    byte[] tmp = new byte[1024];
                    int i = stderr.read(tmp, 0, Math.min(tmp.length, stderr.available()));
                    if (i > 0) {
                        error.append(new String(tmp, 0, i, StandardCharsets.UTF_8));
                    }
                }

                if (channel.isClosed()) {
                    break;
                }

                if (System.currentTimeMillis() - startTime > timeout) {
                    throw new RuntimeException("命令执行超时（" + timeoutSeconds + "秒）: " + command);
                }

                try {
                    Thread.sleep(200); // 减少 CPU 占用，避免忙等
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("命令执行被中断", e);
                }
            }

            int exitStatus = channel.getExitStatus();

            // 合并输出和错误信息
            String fullOutput = output.toString();
            String fullError = error.toString();

            if (exitStatus != 0 || !fullError.isEmpty()) {
                String errorMsg = String.format(
                        "❌ 命令执行失败 [Exit Code: %d]%n" +
                                "📋 命令: %s%n" +
                                "📤 标准输出: %s%n" +
                                "📥 错误输出: %s",
                        exitStatus, command, fullOutput, fullError
                );
                log.error("命令执行失败 [Exit Code: {}] 命令: {} 错误输出: {}", exitStatus, command, fullError);
                throw new RuntimeException(errorMsg);
            }

            return fullOutput;

        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (stdout != null) {
                try {
                    stdout.close();
                } catch (IOException ignored) {}
            }
            if (stderr != null) {
                try {
                    stderr.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 重载方法：默认超时 30 秒
     */
    public String executeCommand(String command) throws JSchException, IOException {
        return executeCommand(command, 30);
    }
    /**
     * 断开连接
     */
    public void disconnect() {
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
            log.info("Channel 已断开");
        }
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("SSH Session 已断开");
        }
    }

    /**
     * 从 top 命令输出中解析 CPU 使用率
     * 兼容 CentOS、OpenEuler、Kylin、Ubuntu、Debian 等主流 Linux 发行版
     * 支持多种 top 输出格式，例如：
     *   - %Cpu(s):  2.3 us,  0.7 sy,  0.0 ni, 96.7 id,  0.3 wa, ...
     *   - Cpu(s):  2.3%us,  0.7%sy,  0.0%ni, 96.7%id,  0.3%wa, ...
     *   - CPU(s):  1.8%us,  0.5%sy, 97.2%id,  0.2%wa, ...
     *
     * @param topOutput top 命令原始输出
     * @return CPU 使用率（百分比数值，范围 0.0 ~ 100.0）
     * @throws RuntimeException 如果无法解析
     */
    public double parseCpuUsageFromTop(String topOutput) {
        if (topOutput == null || topOutput.isEmpty()) {
            throw new IllegalArgumentException("top 输出为空");
        }

        // 使用更通用的正则匹配 CPU 行，忽略大小写和百分号位置
        java.util.regex.Pattern cpuPattern = java.util.regex.Pattern.compile(
            "^(?:%?Cpu|CPU)\\(s\\):\\s*(.+)$", 
            java.util.regex.Pattern.CASE_INSENSITIVE
        );

        String[] lines = topOutput.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            java.util.regex.Matcher matcher = cpuPattern.matcher(line);
            if (matcher.find()) {
                String cpuPart = matcher.group(1).trim();
                log.info("匹配到 CPU 信息行: {}", cpuPart);

                // 尝试通过正则提取 'id' 前的数值（支持 %id 或 id）
                java.util.regex.Pattern idlePattern = java.util.regex.Pattern.compile(
                    "(\\d+(?:\\.\\d+)?)\\s*%?\\s*id"
                );
                java.util.regex.Matcher idleMatcher = idlePattern.matcher(cpuPart);
                if (idleMatcher.find()) {
                    try {
                        double idle = Double.parseDouble(idleMatcher.group(1));
                        double usage = 100.0 - idle;
                        log.info("解析成功 -> Idle: {}%, Usage: {}%", idle, usage);
                        return usage;
                    } catch (NumberFormatException e) {
                        throw new RuntimeException("无法解析 idle 数值: " + idleMatcher.group(1), e);
                    }
                } else {
                    log.warn("匹配到 CPU 行但未找到 'id' 字段: {}", cpuPart);
                }
            }
        }

        throw new RuntimeException("❌ 未在 top 输出中找到有效的 CPU 使用率信息（期望包含 '%Cpu(s):' 或类似）");
    }
}