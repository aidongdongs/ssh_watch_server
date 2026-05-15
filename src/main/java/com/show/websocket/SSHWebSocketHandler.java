package com.show.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.*;
import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;

public class SSHWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SSHWebSocketHandler.class);
    private static final Map<String, SSHConnectionInfo> connections = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    // 添加SystemInfoMapper的静态引用，需要通过setter方法注入
    private static SystemInfoMapper systemInfoMapper;
    
    @Autowired
    public void setSystemInfoMapper(SystemInfoMapper systemInfoMapper) {
        SSHWebSocketHandler.systemInfoMapper = systemInfoMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String uri = session.getUri().toString();
        String[] parts = uri.split("/");
        String serverId = parts[parts.length - 1];
        
        // 如果URL末尾是"ssh"，说明没有提供服务器ID，需要从查询参数或消息中获取
        if ("ssh".equals(serverId)) {
            serverId = null;
        }

        log.info("WebSocket连接已建立，服务器ID: {}", serverId);

        // 如果没有从URL获取到服务器ID，则需要等待客户端发送连接信息
        SystemInfo server = null;
        if (serverId != null) {
            server = systemInfoMapper.selectById(serverId);
        }
        
        if (server == null) {
            log.error("未找到服务器信息，ID: {}", serverId);

            Map<String, Object> errorMessage = new HashMap<>();
            errorMessage.put("type", "error");
            errorMessage.put("content", "未找到服务器信息");
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorMessage)));
            }
            
            session.close();
            return;
        }
        
        String host = server.getHost();
        int port = server.getPort();
        String username = server.getUsername();
        String password = server.getPassword();

        // 建立SSH连接
        JSch jsch = new JSch();
        Session sshSession = jsch.getSession(username, host, port);
        sshSession.setPassword(password);
        sshSession.setConfig("StrictHostKeyChecking", "no");
        // 设置编码以支持中文
        sshSession.setConfig("charset", "UTF-8");
        sshSession.connect();

        // 打开一个通道
        ChannelShell channel = (ChannelShell) sshSession.openChannel("shell");
        channel.setPtyType("xterm");
        // 设置终端环境变量以支持中文
        channel.setEnv("LANG", "zh_CN.UTF-8");
        channel.setEnv("LC_ALL", "zh_CN.UTF-8");
        
        // 获取输入输出流
        OutputStream inputToServer = channel.getOutputStream();
        PrintStream commander = new PrintStream(inputToServer, true, "UTF-8");

        // 设置WebSocket会话和SSH会话的关联
        SSHConnectionInfo connectionInfo = new SSHConnectionInfo(session, sshSession, channel, inputToServer, commander);
        connections.put(session.getId(), connectionInfo);

        // 启动从SSH服务器读取数据并发送到WebSocket客户端的线程
        channel.connect();
        startStreamingOutput(session, channel);

        // 发送连接成功的消息
        Map<String, Object> message = new HashMap<>();
        message.put("type", "terminal");
        message.put("content", "Connected to " + host + "\r\n");
        sendMessage(session, message);
    }

    private void startStreamingOutput(WebSocketSession session, Channel channel) {
        new Thread(() -> {
            try {
                InputStream outFromServer = channel.getInputStream();
                // 使用UTF-8编码读取SSH输出，以支持中文
                InputStreamReader isr = new InputStreamReader(outFromServer, StandardCharsets.UTF_8);
                BufferedReader reader = new BufferedReader(isr);
                char[] buffer = new char[1024];
                int i;
                while ((i = reader.read(buffer)) != -1 && channel.isConnected() && session.isOpen()) {
                    String output = new String(buffer, 0, i);
                    Map<String, Object> message = new HashMap<>();
                    message.put("type", "terminal");
                    message.put("content", output);
                    
                    try {
                        sendMessage(session, message);
                    } catch (Exception e) {
                        log.error("发送消息到WebSocket客户端时出错: {}", e.getMessage());
                        break;
                    }
                }
            } catch (Exception e) {
                log.error("读取SSH输出时出错: {}", e.getMessage());
            }
        }).start();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 检查WebSocket会话是否打开
        if (!session.isOpen()) {
            log.error("WebSocket会话已关闭，无法处理消息");
            return;
        }

        String payload = message.getPayload();
        JsonNode jsonNode = objectMapper.readTree(payload);

        // 添加空指针检查
        if (jsonNode == null || !jsonNode.has("type")) {
            log.error("无效的消息格式: {}", payload);
            return;
        }
        
        String type = jsonNode.get("type").asText();

        SSHConnectionInfo connectionInfo = connections.get(session.getId());
        if (connectionInfo == null) {
            log.error("未找到连接信息");
            return;
        }

        switch (type) {
            case "command":
                // 添加空指针检查
                if (jsonNode.has("content")) {
                    handleCommand(connectionInfo, jsonNode.get("content").asText());
                } else {
                    log.error("命令消息缺少content字段");
                }
                break;
            case "resize":
                if (jsonNode.has("cols") && jsonNode.has("rows")) {
                    handleResize(connectionInfo, jsonNode.get("cols").asInt(), jsonNode.get("rows").asInt());
                }
                break;
            case "fileList":
                handleFileList(connectionInfo, jsonNode.has("path") ? jsonNode.get("path").asText() : "/");
                break;
            case "fileDownload":
                // 添加空指针检查
                if (jsonNode.has("fileName")) {
                    handleFileDownload(connectionInfo, jsonNode.get("fileName").asText(), 
                        jsonNode.has("path") ? jsonNode.get("path").asText() : "/");
                } else {
                    log.error("文件下载消息缺少fileName字段");
                }
                break;
            case "fileDelete":
                // 添加空指针检查
                if (jsonNode.has("fileName")) {
                    handleFileDelete(connectionInfo, jsonNode.get("fileName").asText(),
                        jsonNode.has("path") ? jsonNode.get("path").asText() : "/");
                } else {
                    log.error("文件删除消息缺少fileName字段");
                }
                break;
            default:
                log.error("未知的消息类型: {}", type);
        }
    }

    private void handleCommand(SSHConnectionInfo connectionInfo, String command) {
        try {
            // 检查WebSocket会话是否打开
            if (!connectionInfo.getWebSocketSession().isOpen()) {
                log.error("WebSocket会话已关闭，无法发送命令");
                return;
            }

            connectionInfo.getCommander().print(command);
            connectionInfo.getCommander().flush();
        } catch (Exception e) {
            log.error("发送命令时出错: {}", e.getMessage());
        }
    }

    private void handleResize(SSHConnectionInfo connectionInfo, int cols, int rows) {
        try {
            ChannelShell channel = connectionInfo.getChannel();
            if (channel != null && channel.isConnected()) {
                // 设置终端窗口大小（单位：字符数）
                channel.setPtySize(cols, rows, cols * 8, rows * 18);
                log.info("终端尺寸已调整: {}x{}", cols, rows);
            }
        } catch (Exception e) {
            log.error("调整终端尺寸时出错: {}", e.getMessage());
        }
    }

    private void handleFileList(SSHConnectionInfo connectionInfo, String path) {
        ChannelSftp sftpChannel = null;
        try {
            WebSocketSession session = connectionInfo.getWebSocketSession();
            // 检查WebSocket会话是否打开
            if (!session.isOpen()) {
                log.error("WebSocket会话已关闭，无法处理文件列表请求");
                return;
            }

            Session sshSession = connectionInfo.getSshSession();
            sftpChannel = (ChannelSftp) sshSession.openChannel("sftp");
            sftpChannel.connect();

            // 设置SFTP通道的编码以支持中文文件名
            sftpChannel.setFilenameEncoding("UTF-8");

            Vector<ChannelSftp.LsEntry> files = sftpChannel.ls(path);
            List<Map<String, Object>> fileList = new ArrayList<>();

            for (ChannelSftp.LsEntry file : files) {
                // 跳过当前目录和父目录的条目
                if (".".equals(file.getFilename()) || "..".equals(file.getFilename())) {
                    continue;
                }

                Map<String, Object> fileMap = new HashMap<>();
                fileMap.put("name", file.getFilename());
                fileMap.put("isDirectory", file.getAttrs().isDir());
                fileMap.put("size", file.getAttrs().getSize());
                fileMap.put("permissions", file.getAttrs().getPermissionsString());
                fileList.add(fileMap);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("type", "fileList");
            response.put("files", fileList);

            sendMessage(session, response);
        } catch (Exception e) {
            log.error("获取文件列表时出错", e);

            // 发送错误信息给客户端
            try {
                Map<String, Object> errorMessage = new HashMap<>();
                errorMessage.put("type", "error");
                errorMessage.put("content", "获取文件列表失败: " + e.getMessage());
                sendMessage(connectionInfo.getWebSocketSession(), errorMessage);
            } catch (Exception sendException) {
                log.error("发送错误消息时出错: {}", sendException.getMessage());
            }
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                try {
                    sftpChannel.disconnect();
                } catch (Exception e) {
                    log.error("关闭SFTP通道时出错: {}", e.getMessage());
                }
            }
        }
    }

    private void handleFileDownload(SSHConnectionInfo connectionInfo, String fileName, String path) {
        ChannelSftp sftpChannel = null;
        try {
            WebSocketSession session = connectionInfo.getWebSocketSession();
            // 检查WebSocket会话是否打开
            if (!session.isOpen()) {
                log.error("WebSocket会话已关闭，无法处理文件下载请求");
                return;
            }

            Session sshSession = connectionInfo.getSshSession();
            sftpChannel = (ChannelSftp) sshSession.openChannel("sftp");
            sftpChannel.connect();

            // 设置SFTP通道的编码以支持中文文件名
            sftpChannel.setFilenameEncoding("UTF-8");

            String fullPath = path.equals("/") ? "/" + fileName : path + "/" + fileName;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            sftpChannel.get(fullPath, baos);

            // 将文件内容编码为Base64
            String base64Content = Base64.getEncoder().encodeToString(baos.toByteArray());

            Map<String, Object> response = new HashMap<>();
            response.put("type", "fileDownload");
            response.put("fileName", fileName);
            response.put("content", base64Content);

            sendMessage(session, response);
        } catch (Exception e) {
            log.error("下载文件时出错", e);

            // 发送错误信息给客户端
            try {
                Map<String, Object> errorMessage = new HashMap<>();
                errorMessage.put("type", "error");
                errorMessage.put("content", "文件下载失败: " + e.getMessage());
                sendMessage(connectionInfo.getWebSocketSession(), errorMessage);
            } catch (Exception sendException) {
                log.error("发送错误消息时出错: {}", sendException.getMessage());
            }
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                try {
                    sftpChannel.disconnect();
                } catch (Exception e) {
                    log.error("关闭SFTP通道时出错: {}", e.getMessage());
                }
            }
        }
    }

    private void handleFileDelete(SSHConnectionInfo connectionInfo, String fileName, String path) {
        ChannelSftp sftpChannel = null;
        try {
            WebSocketSession session = connectionInfo.getWebSocketSession();
            // 检查WebSocket会话是否打开
            if (!session.isOpen()) {
                log.error("WebSocket会话已关闭，无法处理文件删除请求");
                return;
            }

            Session sshSession = connectionInfo.getSshSession();
            sftpChannel = (ChannelSftp) sshSession.openChannel("sftp");
            sftpChannel.connect();

            // 设置SFTP通道的编码以支持中文文件名
            sftpChannel.setFilenameEncoding("UTF-8");

            String fullPath = path.equals("/") ? "/" + fileName : path + "/" + fileName;
            sftpChannel.rm(fullPath);

            // 发送删除成功的消息
            Map<String, Object> response = new HashMap<>();
            response.put("type", "fileOperation");
            response.put("message", "文件删除成功: " + fileName);

            sendMessage(session, response);
        } catch (Exception e) {
            log.error("删除文件时出错", e);

            // 发送错误信息给客户端
            try {
                Map<String, Object> errorMessage = new HashMap<>();
                errorMessage.put("type", "error");
                errorMessage.put("content", "文件删除失败: " + e.getMessage());
                sendMessage(connectionInfo.getWebSocketSession(), errorMessage);
            } catch (Exception sendException) {
                log.error("发送错误消息时出错: {}", sendException.getMessage());
            }
        } finally {
            if (sftpChannel != null && sftpChannel.isConnected()) {
                try {
                    sftpChannel.disconnect();
                } catch (Exception e) {
                    log.error("关闭SFTP通道时出错: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("WebSocket连接已关闭: {}, 状态: {}", session.getId(), status);
        SSHConnectionInfo connectionInfo = connections.remove(session.getId());
        if (connectionInfo != null) {
            Channel channel = connectionInfo.getChannel();
            Session sshSession = connectionInfo.getSshSession();
            
            // 安全地关闭连接
            try {
                if (channel != null && channel.isConnected()) {
                    channel.disconnect();
                }
                
                if (sshSession != null && sshSession.isConnected()) {
                    sshSession.disconnect();
                }
            } catch (Exception e) {
                log.error("关闭SSH连接时出错: {}", e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket传输错误", exception);
        
        // 从连接映射中移除并关闭相关资源
        SSHConnectionInfo connectionInfo = connections.remove(session.getId());
        if (connectionInfo != null) {
            Channel channel = connectionInfo.getChannel();
            Session sshSession = connectionInfo.getSshSession();
            
            try {
                if (channel != null && channel.isConnected()) {
                    channel.disconnect();
                }
                
                if (sshSession != null && sshSession.isConnected()) {
                    sshSession.disconnect();
                }
            } catch (Exception e) {
                log.error("处理传输错误时关闭连接出错: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 安全地发送消息到WebSocket客户端
     * @param session WebSocket会话
     * @param message 要发送的消息
     */
    private synchronized void sendMessage(WebSocketSession session, Map<String, Object> message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            } else {
                log.error("WebSocket会话已关闭，无法发送消息");
            }
        } catch (IllegalStateException e) {
            log.error("WebSocket状态错误", e);
        } catch (Exception e) {
            log.error("发送消息到WebSocket客户端时出错", e);
        }
    }
}