package com.show.sftp;

/**
 * 文件条目 DTO
 */
public class FileItem {

    private String name;
    private String path;
    private long size;
    private boolean isDir;
    private String permissions;
    private String lastModified;

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
