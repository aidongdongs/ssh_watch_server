package com.show.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * SFTP 业务服务
 */
@Service
public class SftpService {

    private static final Logger log = LoggerFactory.getLogger(SftpService.class);

    @Autowired
    private SftpSessionManager sessionManager;

    /**
     * 列目录
     */
    public List<FileItem> listFiles(Long serverId, String path) {
        path = SftpUtils.sanitizePath(path);
        ChannelSftp channel = sessionManager.connect(serverId);
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = channel.ls(path);
            List<FileItem> items = new ArrayList<>();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");

            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;

                SftpATTRS attrs = entry.getAttrs();
                FileItem item = new FileItem();
                item.setName(name);
                item.setPath((path.endsWith("/") ? path : path + "/") + name);
                item.setSize(attrs.getSize());
                item.setIsDir(attrs.isDir());
                item.setPermissions(attrs.getPermissionsString());

                if (attrs.getMTime() > 0) {
                    item.setLastModified(sdf.format(new Date(attrs.getMTime() * 1000L)));
                } else {
                    item.setLastModified("-");
                }
                items.add(item);
            }

            // 目录排前，按名称排序
            items.sort((a, b) -> {
                if (a.isIsDir() != b.isIsDir()) return a.isIsDir() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            return items;
        } catch (SftpException e) {
            throw new RuntimeException("列目录失败: " + e.getMessage(), e);
        } finally {
            sessionManager.disconnect(channel);
        }
    }

    /**
     * 下载文件，写入输出流
     */
    public void downloadFile(Long serverId, String path, OutputStream outputStream) {
        path = SftpUtils.sanitizePath(path);
        ChannelSftp channel = sessionManager.connect(serverId);
        try {
            channel.get(path, outputStream);
        } catch (SftpException e) {
            throw new RuntimeException("下载文件失败: " + e.getMessage(), e);
        } finally {
            sessionManager.disconnect(channel);
        }
    }

    /**
     * 删除文件或目录
     */
    public void deleteFile(Long serverId, String path, String type) {
        path = SftpUtils.sanitizePath(path);
        ChannelSftp channel = sessionManager.connect(serverId);
        try {
            if ("dir".equals(type)) {
                channel.rmdir(path);
            } else {
                channel.rm(path);
            }
            log.info("删除成功: {} ({})", path, type);
        } catch (SftpException e) {
            throw new RuntimeException("删除失败: " + e.getMessage(), e);
        } finally {
            sessionManager.disconnect(channel);
        }
    }

    /**
     * 重命名文件或目录
     */
    public void renameFile(Long serverId, String oldPath, String newName) {
        oldPath = SftpUtils.sanitizePath(oldPath);
        ChannelSftp channel = sessionManager.connect(serverId);
        try {
            String parent = oldPath.contains("/") ? oldPath.substring(0, oldPath.lastIndexOf('/')) : "";
            String newPath = (parent.isEmpty() ? "" : parent + "/") + newName;
            channel.rename(oldPath, newPath);
            log.info("重命名成功: {} -> {}", oldPath, newPath);
        } catch (SftpException e) {
            throw new RuntimeException("重命名失败: " + e.getMessage(), e);
        } finally {
            sessionManager.disconnect(channel);
        }
    }

    /**
     * 新建文件夹
     */
    public void createDirectory(Long serverId, String path) {
        path = SftpUtils.sanitizePath(path);
        ChannelSftp channel = sessionManager.connect(serverId);
        try {
            channel.mkdir(path);
            log.info("新建文件夹成功: {}", path);
        } catch (SftpException e) {
            // 目录已存在也视为成功（部分 SFTP 服务器返回 SSH_FX_FAILURE 而非 FILE_ALREADY_EXISTS）
            try {
                SftpATTRS attrs = channel.stat(path);
                if (attrs != null && attrs.isDir()) {
                    log.info("文件夹已存在: {}", path);
                    return;
                }
            } catch (SftpException ignored) {
                // stat 失败说明目录确实不存在，抛出原异常
            }
            throw new RuntimeException("新建文件夹失败: " + e.getMessage(), e);
        } finally {
            sessionManager.disconnect(channel);
        }
    }
}
