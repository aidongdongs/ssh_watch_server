package com.show.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.*;
import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket ↔ SSH 桥接处理器
 * 将浏览器的 WebSocket 连接映射到远程服务器的 SSH Shell，实现 Web 终端功能
 */
@Component
public class SSHWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SSHWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // sessionId → SSH连接信息，维护所有活跃的 WebSocket-SSH 映射
    private final Map<String, SSHConnectionInfo> connections = new ConcurrentHashMap<>();

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    // ========== WebSocket 生命周期 ==========

    // 连接建立：解析服务器ID → 建立 SSH 连接 → 启动输出流推送
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String serverId = extractServerId(session);
        log.info("WebSocket连接已建立，服务器ID: {}", serverId);

        SystemInfo server = serverId != null ? systemInfoMapper.selectById(serverId) : null;
        if (server == null) {
            log.error("未找到服务器信息，ID: {}", serverId);
            sendMessage(session, createMessage("error", "未找到服务器信息"));
            session.close();
            return;
        }

        // 建立SSH连接（设置 10 秒超时）
        JSch jsch = new JSch();
        Session sshSession = jsch.getSession(server.getUsername(), server.getHost(), server.getPort());
        sshSession.setPassword(server.getPassword());
        sshSession.setConfig("StrictHostKeyChecking", "no");
        sshSession.setConfig("charset", "UTF-8");
        sshSession.setTimeout(10000);
        sshSession.connect(10000);

        // 打开 shell 通道（设置 5 秒超时）
        ChannelShell channel = (ChannelShell) sshSession.openChannel("shell");
        channel.setPtyType("xterm");
        channel.setEnv("LANG", "zh_CN.UTF-8");
        channel.setEnv("LC_ALL", "zh_CN.UTF-8");

        OutputStream inputToServer = channel.getOutputStream();
        PrintStream commander = new PrintStream(inputToServer, true, "UTF-8");

        SSHConnectionInfo connectionInfo = new SSHConnectionInfo(session, sshSession, channel, inputToServer, commander);
        connections.put(session.getId(), connectionInfo);

        channel.connect();
        startStreamingOutput(session, channel);

        sendMessage(session, createMessage("terminal", "Connected to " + server.getHost() + "\r\n"));
    }

    // 从 URL 路径中提取服务器ID（格式: /ssh/{serverId}）
    private String extractServerId(WebSocketSession session) {
        java.net.URI uri = session.getUri();
        if (uri == null) return null;
        String[] parts = uri.toString().split("/");
        String last = parts[parts.length - 1];
        return "ssh".equals(last) ? null : last;
    }

    // 启动后台线程：持续读取 SSH 通道输出并推送到 WebSocket 客户端
    private void startStreamingOutput(WebSocketSession session, Channel channel) {
        Thread worker = new Thread(() -> {
            InputStream in = null;
            try {
                in = channel.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                char[] buffer = new char[1024];
                int len;
                while ((len = reader.read(buffer)) != -1 && channel.isConnected() && session.isOpen()) {
                    sendMessage(session, createMessage("terminal", new String(buffer, 0, len)));
                }
            } catch (Exception e) {
                if (!"Socket closed".equals(e.getMessage())) {
                    log.error("读取SSH输出时出错: {}", e.getMessage());
                }
            } finally {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
        }, "ssh-stream-" + session.getId());
        worker.setDaemon(true);
        worker.start();
    }

    // 收到 WebSocket 消息：解析 JSON 并分发到对应的 handler
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!session.isOpen()) return;

        String payload = message.getPayload();
        JsonNode jsonNode = objectMapper.readTree(payload);
        if (jsonNode == null || !jsonNode.has("type")) {
            log.error("无效的消息格式: {}", payload);
            return;
        }

        SSHConnectionInfo conn = connections.get(session.getId());
        if (conn == null) {
            log.error("未找到连接信息");
            return;
        }

        String type = jsonNode.get("type").asText();
        switch (type) {
            case "command":
                // 将用户输入的命令发送到 SSH 通道
                if (jsonNode.has("content")) {
                    handleCommand(conn, jsonNode.get("content").asText());
                }
                break;
            case "resize":
                // 终端窗口尺寸变化通知 PTY
                if (jsonNode.has("cols") && jsonNode.has("rows")) {
                    handleResize(conn, jsonNode.get("cols").asInt(), jsonNode.get("rows").asInt());
                }
                break;
            default:
                log.error("未知的消息类型: {}", type);
        }
    }

    // 发送命令到 SSH Shell
    private void handleCommand(SSHConnectionInfo conn, String command) {
        try {
            if (!conn.getWebSocketSession().isOpen()) return;
            conn.getCommander().print(command);
            conn.getCommander().flush();
        } catch (Exception e) {
            log.error("发送命令时出错: {}", e.getMessage());
        }
    }

    // 调整终端窗口大小（通知 SSH PTY）
    private void handleResize(SSHConnectionInfo conn, int cols, int rows) {
        try {
            ChannelShell channel = conn.getChannel();
            if (channel != null && channel.isConnected()) {
                channel.setPtySize(cols, rows, cols * 8, rows * 18);
                log.info("终端尺寸已调整: {}x{}", cols, rows);
            }
        } catch (Exception e) {
            log.error("调整终端尺寸时出错: {}", e.getMessage());
        }
    }

    // WebSocket 关闭：清理 SSH 连接资源
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket连接已关闭: {}, 状态: {}", session.getId(), status);
        cleanup(session.getId());
    }

    // WebSocket 传输异常：清理 SSH 连接资源
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误", exception);
        cleanup(session.getId());
    }

    // 移除连接映射并关闭 SSH 通道和会话
    private void cleanup(String sessionId) {
        SSHConnectionInfo conn = connections.remove(sessionId);
        if (conn == null) return;
        quietlyClose(conn.getChannel());
        quietlyClose(conn.getSshSession());
    }

    // 安全关闭 Channel（忽略已关闭等异常）
    private void quietlyClose(Channel channel) {
        try {
            if (channel != null && channel.isConnected()) channel.disconnect();
        } catch (Exception e) {
            log.debug("关闭通道时忽略异常", e);
        }
    }

    // 安全关闭 SSH Session（忽略已关闭等异常）
    private void quietlyClose(Session sshSession) {
        try {
            if (sshSession != null && sshSession.isConnected()) sshSession.disconnect();
        } catch (Exception e) {
            log.debug("关闭SSH会话时忽略异常", e);
        }
    }

    // 构造 {type, content} 格式的 JSON 消息
    private static Map<String, Object> createMessage(String type, String content) {
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", type);
        msg.put("content", content);
        return msg;
    }

    // 发送 JSON 消息到 WebSocket 客户端（synchronized 防止并发写入冲突）
    private synchronized void sendMessage(WebSocketSession session, Map<String, Object> message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            }
        } catch (Exception e) {
            log.error("发送消息到WebSocket客户端时出错", e);
        }
    }
}