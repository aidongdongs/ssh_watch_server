package com.show.sftp;

/**
 * 文件条目 DTO，用于在前端和后端之间传递文件/目录信息
 * 字段与 SFTP LsEntry 属性一一对应，前后端共用同一数据结构
 */
public class FileItem {

    private String name;          // 文件名
    private String path;          // 完整路径
    private long size;            // 文件大小（字节）
    private boolean isDir;        // 是否为目录
    private String permissions;   // 权限字符串，如 rwxr-xr-x
    private String lastModified;  // 最后修改时间

    public FileItem() {}

    public FileItem(String name, String path, long size, boolean isDir, String permissions, String lastModified) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.isDir = isDir;
        this.permissions = permissions;
        this.lastModified = lastModified;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public boolean isIsDir() { return isDir; }
    public void setIsDir(boolean isDir) { this.isDir = isDir; }
    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }
    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }
}
