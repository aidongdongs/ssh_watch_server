package com.show.entity;

/**
 * 磁盘使用情况实体类，对应 disk_usage 表
 */
public class DiskUsage {
    private Long id;
    private Long monitorId;       // 对应 system_monitor.id，外键关联
    private String filesystem;    // 如 /dev/vda1
    private String type;          // 文件系统类型，如ext4
    private String size;          // 如 "43G"
    private String used;          // 如 "9.4G"
    private String avail;         // 如 "31G"
    private Integer usagePercent; // 如 24
    private String mountedOn;     // 如 /
    private String createdAt;

    // Constructors
    public DiskUsage() {
    }

    public DiskUsage(Long monitorId, String filesystem, String size, String used, String available, Integer usagePercent, String mountedOn) {
        this.monitorId = monitorId;
        this.filesystem = filesystem;
        this.size = size;
        this.used = used;
        this.avail = available;
        this.usagePercent = usagePercent;
        this.mountedOn = mountedOn;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMonitorId() {
        return monitorId;
    }

    public void setMonitorId(Long monitorId) {
        this.monitorId = monitorId;
    }

    public String getFilesystem() {
        return filesystem;
    }

    public void setFilesystem(String filesystem) {
        this.filesystem = filesystem;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getUsed() {
        return used;
    }

    public void setUsed(String used) {
        this.used = used;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAvail() {
        return avail;
    }

    public void setAvail(String avail) {
        this.avail = avail;
    }

    public Integer getUsagePercent() {
        return usagePercent;
    }

    public void setUsagePercent(Integer usagePercent) {
        this.usagePercent = usagePercent;
    }

    public String getMountedOn() {
        return mountedOn;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public void setMountedOn(String mountedOn) {
        this.mountedOn = mountedOn;
    }


    @Override
    public String toString() {
        return "DiskUsage{" +
                "id=" + id +
                ", monitorId=" + monitorId +
                ", filesystem='" + filesystem + '\'' +
                ", size='" + size + '\'' +
                ", used='" + used + '\'' +
                ", avail='" + avail + '\'' +
                ", usagePercent=" + usagePercent +
                ", mountedOn='" + mountedOn + '\'' +
                ", createdAt='" + createdAt + '\'' +
                '}';
    }
}