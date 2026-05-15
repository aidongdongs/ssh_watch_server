package com.show.entity;

import java.util.List;

/**
 * 采集结果封装类，包含系统信息与磁盘使用列表
 */
public class CollectResult {
    private SystemInfo systemInfo;
    private List<DiskUsage> diskUsages;
    private boolean error;
    private String errorMessage;

    /**
     * 构造正常采集结果
     */
    public CollectResult(SystemInfo systemInfo, List<DiskUsage> diskUsages) {
        this.systemInfo = systemInfo;
        this.diskUsages = diskUsages;
        this.error = false;
    }

    /**
     * 构造异常采集结果
     */
    public CollectResult(SystemInfo systemInfo, String errorMessage) {
        this.systemInfo = systemInfo;
        this.diskUsages = null;
        this.error = true;
        this.errorMessage = errorMessage;
    }

    public SystemInfo getSystemInfo() { return systemInfo; }
    public List<DiskUsage> getDiskUsages() { return diskUsages; }
    public boolean isError() { return error; }
    public String getErrorMessage() { return errorMessage; }
}
