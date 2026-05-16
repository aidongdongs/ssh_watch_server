package com.show.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 上传会话状态对象
 * 每个 WebSocket 连接对应一个 UploadSession，跟踪单次或队列上传的进度和资源
 * 支持队列模式：同一 SSH 连接内串行上传多个文件，复用 ChannelSftp
 */
public class UploadSession {

    private static final Logger log = LoggerFactory.getLogger(UploadSession.class);

    private String wsSessionId;
    private String remoteFilePath;
    private long fileSize;
    private long receivedBytes;
    private long startTime;
    private Session sshSession;
    private ChannelSftp channel;
    private OutputStream outputStream;
    private boolean completed;

    // 队列模式字段：支持多文件串行上传，复用同一 SSH 连接
    private int queueIndex = 1;         // 当前文件在队列中的序号
    private int queueTotal = 1;         // 队列总文件数
    private long queueTotalBytes;       // 队列所有文件的总字节数
    private long queueStartTime;        // 队列开始时间戳

    public UploadSession() {}

    public UploadSession(String wsSessionId, String remoteFilePath, long fileSize,
                          Session sshSession, ChannelSftp channel, OutputStream outputStream) {
        this.wsSessionId = wsSessionId;
        this.remoteFilePath = remoteFilePath;
        this.fileSize = fileSize;
        this.receivedBytes = 0;
        this.startTime = System.currentTimeMillis();
        this.sshSession = sshSession;
        this.channel = channel;
        this.outputStream = outputStream;
        this.completed = false;
    }

    /**
     * 清理所有资源：关闭输出流 → 如有需要删除远程不完整文件 → 断开 Channel → 断开 SSH Session
     * @param deletePartialFile true=上传未完成时删除远程不完整文件，false=正常完成保留文件
     */
    public void closeAndCleanup(boolean deletePartialFile) {
        if (outputStream != null) {
            try { outputStream.close(); } catch (IOException ignored) {}
        }
        if (deletePartialFile && !completed && remoteFilePath != null && channel != null && channel.isConnected()) {
            try { channel.rm(remoteFilePath); } catch (SftpException ignored) {
                log.debug("删除不完整文件忽略异常: {}", remoteFilePath);
            }
        }
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (sshSession != null && sshSession.isConnected()) {
            sshSession.disconnect();
        }
    }

    /**
     * 不关闭 SSH 连接，仅重置状态准备上传下一个文件（队列模式复用）
     * 关闭旧 OutputStream → 调用 channel.put() 打开新文件输出流
     */
    public void resetForNextFile(String newRemotePath, long newFileSize) {
        this.remoteFilePath = newRemotePath;
        this.fileSize = newFileSize;
        this.receivedBytes = 0;
        this.startTime = System.currentTimeMillis();
        this.completed = false;
        try {
            if (this.outputStream != null) {
                this.outputStream.close();
            }
            if (this.channel == null || !this.channel.isConnected()) {
                throw new RuntimeException("SFTP 通道已断开，无法继续队列上传");
            }
            this.outputStream = this.channel.put(newRemotePath);
        } catch (Exception e) {
            throw new RuntimeException("切换队列文件失败: " + e.getMessage(), e);
        }
    }

    // === getters & setters ===

    public String getWsSessionId() { return wsSessionId; }
    public void setWsSessionId(String wsSessionId) { this.wsSessionId = wsSessionId; }
    public String getRemoteFilePath() { return remoteFilePath; }
    public void setRemoteFilePath(String remoteFilePath) { this.remoteFilePath = remoteFilePath; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }
    public long getReceivedBytes() { return receivedBytes; }
    public void setReceivedBytes(long receivedBytes) { this.receivedBytes = receivedBytes; }
    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public Session getSshSession() { return sshSession; }
    public void setSshSession(Session sshSession) { this.sshSession = sshSession; }
    public ChannelSftp getChannel() { return channel; }
    public void setChannel(ChannelSftp channel) { this.channel = channel; }
    public OutputStream getOutputStream() { return outputStream; }
    public void setOutputStream(OutputStream outputStream) { this.outputStream = outputStream; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public int getQueueIndex() { return queueIndex; }
    public void setQueueIndex(int queueIndex) { this.queueIndex = queueIndex; }
    public int getQueueTotal() { return queueTotal; }
    public void setQueueTotal(int queueTotal) { this.queueTotal = queueTotal; }
    public long getQueueTotalBytes() { return queueTotalBytes; }
    public void setQueueTotalBytes(long queueTotalBytes) { this.queueTotalBytes = queueTotalBytes; }
    public long getQueueStartTime() { return queueStartTime; }
    public void setQueueStartTime(long queueStartTime) { this.queueStartTime = queueStartTime; }
}
