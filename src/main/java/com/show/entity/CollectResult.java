package com.show.entity;

import java.util.List;

public class CollectResult {
    private SystemInfo systemInfo;
    private List<DiskUsage> diskUsages;
    private boolean error;
    private String errorMessage;

    public CollectResult(SystemInfo systemInfo, List<DiskUsage> diskUsages) {
        this.systemInfo = systemInfo;
        this.diskUsages = diskUsages;
        this.error = false;
    }

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
