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
     * 清理所有资源：关闭输出流 → 删除不完整文件 → 断开 SSH
     * @param deletePartialFile true=上传未完成时删除远程不完整文件
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
}
