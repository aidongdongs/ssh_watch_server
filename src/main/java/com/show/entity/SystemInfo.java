package com.show.entity;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class SystemInfo {
    private Long id;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String topInfo;
    private Double cpuUsage;
    private String freeInfo;
    private String memoryUsage;
    private String diskInfo;
    private Integer diskUsagePercent;
    /** CPU使用率前三的进程信息（由 top -bn 1 解析得到，格式: "PID %CPU COMMAND"） */
    private String topProcesses;
    /** 内存使用率前三的进程信息（由 top -bn 1 解析得到，格式: "PID %MEM COMMAND"） */
    private String topMemProcesses;
    private String createdAt;
    private List<DiskUsage> diskUsages;

    public SystemInfo() {
    }

    public SystemInfo(String host, Integer port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTopInfo() {
        return topInfo;
    }

    public void setTopInfo(String topInfo) {
        this.topInfo = topInfo;
    }

    public Double getCpuUsage() {
        return cpuUsage;
    }

    public void setCpuUsage(Double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public String getFreeInfo() {
        return freeInfo;
    }

    public void setFreeInfo(String freeInfo) {
        this.freeInfo = freeInfo;
    }

    public String getMemoryUsage() {
        return memoryUsage;
    }

    public void setMemoryUsage(String memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public String getDiskInfo() {
        return diskInfo;
    }

    public void setDiskInfo(String diskInfo) {
        this.diskInfo = diskInfo;
    }

    public Integer getDiskUsagePercent() {
        return diskUsagePercent;
    }

    public void setDiskUsagePercent(Integer diskUsagePercent) {
        this.diskUsagePercent = diskUsagePercent;
    }

    public String getTopProcesses() {
        return topProcesses;
    }

    public void setTopProcesses(String topProcesses) {
        this.topProcesses = topProcesses;
    }

    public String getTopMemProcesses() {
        return topMemProcesses;
    }

    public void setTopMemProcesses(String topMemProcesses) {
        this.topMemProcesses = topMemProcesses;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public List<DiskUsage> getDiskUsages() {
        return diskUsages;
    }

    public void setDiskUsages(List<DiskUsage> diskUsages) {
        this.diskUsages = diskUsages;
    }

    @Override
    public String toString() {
        return "SystemInfo{" +
                "id=" + id +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", username='" + username + '\'' +
                ", password='" + password + '\'' +
                ", topInfo='" + topInfo + '\'' +
                ", cpuUsage=" + cpuUsage +
                ", freeInfo='" + freeInfo + '\'' +
                ", memoryUsage='" + memoryUsage + '\'' +
                ", diskInfo='" + diskInfo + '\'' +
                ", diskUsagePercent=" + diskUsagePercent +
                ", topProcesses='" + topProcesses + '\'' +
                ", topMemProcesses='" + topMemProcesses + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}