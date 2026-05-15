package com.show.sftp;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SftpUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 校验并清理路径，防止路径穿越攻击
     * 所有接收 path 参数的接口入口处调用
     */
    public static String sanitizePath(String path) {
        if (path == null || path.trim().isEmpty()) return "/";
        if (path.contains("..")) {
            throw new IllegalArgumentException("路径不合法: 不允许包含 '..'");
        }
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }
}
