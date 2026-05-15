package com.show.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SFTP 连接会话管理
 */
@Component
public class SftpSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SftpSessionManager.class);

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    /**
     * 根据 serverId 建立 SFTP 连接，返回 ChannelSftp
     */
    public ChannelSftp connect(Long serverId) {
        SystemInfo server = systemInfoMapper.selectById(String.valueOf(serverId));
        if (server == null) {
            throw new IllegalArgumentException("服务器不存在, id=" + serverId);
        }
        try {
            JSch jsch = new JSch();
            Session sshSession = jsch.getSession(server.getUsername(), server.getHost(), server.getPort());
            sshSession.setPassword(server.getPassword());
            sshSession.setConfig("StrictHostKeyChecking", "no");
            sshSession.connect(15000);

            ChannelSftp channel = (ChannelSftp) sshSession.openChannel("sftp");
            channel.connect(10000);
            log.debug("SFTP连接成功: {}:{}", server.getHost(), server.getPort());
            return channel;
        } catch (JSchException e) {
            throw new RuntimeException("SFTP连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 断开 SFTP 连接
     */
    public void disconnect(ChannelSftp channel) {
        if (channel != null) {
            try {
                Session sshSession = channel.getSession();
                channel.disconnect();
                if (sshSession != null && sshSession.isConnected()) {
                    sshSession.disconnect();
                }
            } catch (Exception e) {
                log.debug("断开SFTP连接时忽略异常", e);
            }
        }
    }
}
