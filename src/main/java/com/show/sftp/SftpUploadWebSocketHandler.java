package com.show.sftp;

import com.jcraft.jsch.*;
import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 分块上传处理器
 * 协议：upload_start(Text) → upload_ready(Text) → binary chunks → upload_progress(Text) → ... → upload_complete(Text)
 * 支持多文件队列上传（串行，复用 SSH 连接）
 */
@Component
public class SftpUploadWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SftpUploadWebSocketHandler.class);

    // 活跃上传会话 wsSessionId → UploadSession
    private final ConcurrentHashMap<String, UploadSession> uploads = new ConcurrentHashMap<>();

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        log.debug("SFTP上传WebSocket连接建立: {}", wsSession.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) throws Exception {
        JsonNode json = SftpUtils.OBJECT_MAPPER.readTree(message.getPayload());
        String type = json.get("type").asText();

        switch (type) {
            case "upload_start": {
                // 从 HandshakeInterceptor 注入的 session attributes 中获取 serverId
                String serverId = (String) ws.getAttributes().get("serverId");
                String rawPath = json.get("remotePath").asText();
                String remotePath = SftpUtils.sanitizePath(rawPath);
                long fileSize = json.get("fileSize").asLong();
                int queueIndex = json.has("queueIndex") ? json.get("queueIndex").asInt() : 1;
                int queueTotal = json.has("queueTotal") ? json.get("queueTotal").asInt() : 1;

                // 队列模式：检查是否有可复用的连接（上一个文件已完成）
                UploadSession existing = uploads.get(ws.getId());
                if (existing != null && existing.isCompleted() && queueIndex > 1) {
                    try {
                        existing.resetForNextFile(remotePath, fileSize);
                        existing.setQueueIndex(queueIndex);
                        existing.setQueueTotal(queueTotal);
                        if (existing.getQueueTotalBytes() == 0 && json.has("queueTotalBytes")) {
                            existing.setQueueTotalBytes(json.get("queueTotalBytes").asLong());
                        }
                        sendJson(ws, Collections.singletonMap("type", "upload_ready"));
                        return;
                    } catch (Exception e) {
                        log.error("队列模式复用连接失败，降级为重建", e);
                        existing.closeAndCleanup(true);
                        uploads.remove(ws.getId());
                    }
                }

                // 清理残留的上一次出错会话
                if (existing != null) {
                    existing.closeAndCleanup(true);
                    uploads.remove(ws.getId());
                }

                // 首次上传或降级后重建：建立全新 SSH+SFTP 连接
                try {
                    SystemInfo server = systemInfoMapper.selectById(serverId);
                    if (server == null) {
                        sendJson(ws, errorMsg("服务器不存在, id=" + serverId));
                        return;
                    }

                    JSch jsch = new JSch();
                    Session sshSession = jsch.getSession(server.getUsername(), server.getHost(), server.getPort());
                    sshSession.setPassword(server.getPassword());
                    sshSession.setConfig("StrictHostKeyChecking", "no");
                    sshSession.connect(15000);

                    ChannelSftp channel = (ChannelSftp) sshSession.openChannel("sftp");
                    channel.connect(10000);

                    OutputStream out = channel.put(remotePath);

                    UploadSession session = new UploadSession(ws.getId(), remotePath,
                            fileSize, sshSession, channel, out);
                    session.setQueueIndex(queueIndex);
                    session.setQueueTotal(queueTotal);
                    if (json.has("queueTotalBytes")) {
                        session.setQueueTotalBytes(json.get("queueTotalBytes").asLong());
                    } else {
                        session.setQueueTotalBytes(fileSize);
                    }
                    session.setQueueStartTime(System.currentTimeMillis());
                    uploads.put(ws.getId(), session);

                    sendJson(ws, Collections.singletonMap("type", "upload_ready"));
                } catch (Exception e) {
                    log.error("上传开始失败", e);
                    sendJson(ws, errorMsg("上传初始化失败: " + e.getMessage()));
                }
                break;
            }
            case "upload_cancel":
                // 用户取消上传，删除远程不完整文件并断开连接
                UploadSession cancelled = uploads.remove(ws.getId());
                if (cancelled != null) cancelled.closeAndCleanup(true);
                break;
            default:
                log.warn("未知的文本消息类型: {}", type);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        UploadSession session = uploads.get(ws.getId());
        if (session == null || session.isCompleted()) return;

        // 过渡期保护：队列切换文件后，丢弃过快到达的二进制帧（sftp.md §12）
        // 协议保证客户端在收到 upload_ready 后才发帧，因此过渡期内到达的帧均为残留数据
        if (session.isTransitioning()) {
            long elapsed = System.currentTimeMillis() - session.getTransitionTime();
            if (elapsed < 20) {
                log.warn("过渡期内收到二进制帧(elapsed={}ms)，疑似残留帧，已丢弃. sessionId={}",
                        elapsed, ws.getId());
                return;
            }
            session.setTransitioning(false);
        }

        try {
            // 读取二进制帧数据
            byte[] bytes = new byte[message.getPayload().remaining()];
            message.getPayload().get(bytes);

            // 写入 SFTP 输出流（直接写到远程服务器，不缓冲）
            session.getOutputStream().write(bytes);
            session.getOutputStream().flush();

            // 更新进度
            session.setReceivedBytes(session.getReceivedBytes() + bytes.length);
            double percent = (double) session.getReceivedBytes() / session.getFileSize() * 100;

            // 回复进度确认给客户端（同时也是滑动窗口流控信号）
            Map<String, Object> progressMsg = new HashMap<>();
            progressMsg.put("type", "upload_progress");
            progressMsg.put("received", session.getReceivedBytes());
            progressMsg.put("percent", percent);
            sendJson(ws, progressMsg);

            // 检查文件是否传输完毕
            if (session.getReceivedBytes() >= session.getFileSize()) {
                session.setCompleted(true);
                long cost = System.currentTimeMillis() - session.getStartTime();
                String speed = String.format("%.1fMB/s",
                        (double) session.getFileSize() / cost / 1024);

                Map<String, Object> completeMsg = new HashMap<>();
                completeMsg.put("type", "upload_complete");
                completeMsg.put("fileSize", session.getFileSize());
                completeMsg.put("timeMs", cost);
                completeMsg.put("speed", speed);

                boolean isLastInQueue = (session.getQueueIndex() >= session.getQueueTotal());

                if (isLastInQueue) {
                    // 最后一个文件（含单文件模式）→ 发送单文件完成 + 队列汇总
                    sendJson(ws, completeMsg);
                    long queueCost = System.currentTimeMillis() - session.getQueueStartTime();
                    long totalBytes = session.getQueueTotalBytes() > 0
                            ? session.getQueueTotalBytes() : session.getFileSize();
                    String queueSpeed = String.format("%.1fMB/s",
                            (double) totalBytes / queueCost / 1024);
                    Map<String, Object> queueMsg = new HashMap<>();
                    queueMsg.put("type", "upload_queue_complete");
                    queueMsg.put("totalFiles", session.getQueueTotal());
                    queueMsg.put("totalBytes", totalBytes);
                    queueMsg.put("totalTimeMs", queueCost);
                    queueMsg.put("speed", queueSpeed);
                    sendJson(ws, queueMsg);
                    session.closeAndCleanup(false);
                    uploads.remove(ws.getId());
                } else {
                    // 队列中间文件 → 仅关闭 OutputStream，保留 SSH 连接供下一个文件复用
                    sendJson(ws, completeMsg);
                    try { session.getOutputStream().close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log.error("处理二进制消息异常", e);
            sendJson(ws, errorMsg("上传失败: " + e.getMessage()));
            UploadSession failed = uploads.remove(ws.getId());
            if (failed != null) failed.closeAndCleanup(true);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        log.debug("SFTP上传WebSocket关闭: {}", ws.getId());
        UploadSession session = uploads.remove(ws.getId());
        if (session != null) {
            session.closeAndCleanup(true);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable exception) {
        log.error("SFTP上传WebSocket传输异常", exception);
        UploadSession session = uploads.remove(ws.getId());
        if (session != null) {
            session.closeAndCleanup(true);
        }
    }

    /**
     * Spring 容器关闭时，清理所有残留的 SFTP 上传连接，防止连接泄漏
     */
    @PreDestroy
    public void destroy() {
        log.info("Spring容器关闭，清理所有SFTP上传连接");
        for (String id : new HashSet<>(uploads.keySet())) {
            UploadSession session = uploads.remove(id);
            if (session != null) {
                session.closeAndCleanup(true);
            }
        }
    }

    /**
     * 向客户端发送 JSON 文本消息，忽略发送时的 IO 异常
     */
    private void sendJson(WebSocketSession ws, Map<String, Object> msg) {
        try {
            String json = SftpUtils.OBJECT_MAPPER.writeValueAsString(msg);
            ws.sendMessage(new TextMessage(json));
        } catch (IOException ignored) {
            log.debug("发送JSON消息忽略异常");
        }
    }

    /**
     * 构造上传错误消息
     */
    private Map<String, Object> errorMsg(String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "upload_error");
        m.put("message", message);
        return m;
    }
}
