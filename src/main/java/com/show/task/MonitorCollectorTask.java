package com.show.task;

import com.jcraft.jsch.JSchException;
import com.show.entity.CollectResult;
import com.show.entity.DiskUsage;
import com.show.entity.SystemInfo;
import com.show.util.DiskUtil;
import com.show.util.MemoryUtil;
import com.show.util.SSHUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class MonitorCollectorTask implements Callable<CollectResult> {

    private static final Logger log = LoggerFactory.getLogger(MonitorCollectorTask.class);

    private final SystemInfo systemInfo;

    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MonitorCollectorTask(SystemInfo systemInfo) {
        this.systemInfo = systemInfo;
    }

    @Override
    public CollectResult call() {
        String startTime = LocalDateTime.now().format(LOG_TIME_FORMATTER);
        log.info("[{}] 开始采集服务器: {}:{}", startTime, systemInfo.getHost(), systemInfo.getPort());

        SSHUtil sshUtil = new SSHUtil();

        try {
            sshUtil.connect(systemInfo.getHost(), systemInfo.getPort(), systemInfo.getUsername(), systemInfo.getPassword());

            // 1. 获取内存信息
            String memBytes = sshUtil.executeCommand("free -b");
            String memInfo = sshUtil.executeCommand("free -h");
            systemInfo.setFreeInfo(memInfo);

            Map<String, Object> memInfoMap = MemoryUtil.parseMemoryUsage(memBytes);
            if (memInfoMap.containsKey("mem_total")) {
                Long total = (Long) memInfoMap.get("mem_total");
                Long used = (Long) memInfoMap.get("mem_used");
                Double usagePercent = (Double) memInfoMap.get("mem_usage_percent");
                String free = MemoryUtil.formatBytes(total) + "/" + MemoryUtil.formatBytes(used) + " 使用率: " + String.format("%.2f%%", usagePercent);
                systemInfo.setMemoryUsage(free);
            }

            // 2. 获取磁盘信息
            String disk = sshUtil.executeCommand("df -hT");
            systemInfo.setDiskInfo(disk);
            List<Map<String, Object>> diskMaps = DiskUtil.parseDiskUsage(disk);

            // 计算整体磁盘使用百分比
            Integer diskUsagePercent = calculateOverallDiskUsage(diskMaps);
            systemInfo.setDiskUsagePercent(diskUsagePercent);

            // 3. 获取 CPU 信息
            String top = sshUtil.executeCommand("top -n 1 -b");
            double cpuUsage = sshUtil.parseCpuUsageFromTop(top);
            systemInfo.setTopInfo(top);
            systemInfo.setCpuUsage(cpuUsage);

            // 4. 设置采集时间
            systemInfo.setCreatedAt(LocalDateTime.now().format(LOG_TIME_FORMATTER));

            // 5. 转换磁盘分区数据
            List<DiskUsage> diskUsages = new ArrayList<>();
            for (Map<String, Object> map : diskMaps) {
                DiskUsage diskUsage = new DiskUsage();
                diskUsage.setFilesystem((String) map.get("filesystem"));
                diskUsage.setType((String) map.get("type"));
                diskUsage.setMountedOn((String) map.get("mounted_on"));
                diskUsage.setSize((String) map.get("size"));
                diskUsage.setUsed((String) map.get("used"));
                diskUsage.setAvail((String) map.get("avail"));
                diskUsage.setUsagePercent((Integer) map.get("use_percent"));
                diskUsage.setMonitorId(systemInfo.getId());
                diskUsage.setCreatedAt(LocalDateTime.now().format(LOG_TIME_FORMATTER));
                diskUsages.add(diskUsage);
            }

            String endTime = LocalDateTime.now().format(LOG_TIME_FORMATTER);
            log.info("[{}] 服务器采集完成: {}:{} (耗时: {})", endTime, systemInfo.getHost(), systemInfo.getPort(), getDuration(startTime));

            return new CollectResult(systemInfo, diskUsages);

        } catch (JSchException e) {
            log.error("SSH连接失败: {}", e.getMessage());
            return new CollectResult(systemInfo, "SSH连接失败: " + e.getMessage());
        } catch (IOException e) {
            log.error("命令执行失败: {}", e.getMessage());
            return new CollectResult(systemInfo, "命令执行失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("未知错误", e);
            return new CollectResult(systemInfo, "未知错误: " + e.getMessage());
        } finally {
            if (sshUtil.isConnected()) {
                sshUtil.disconnect();
            }
        }
    }

    private Integer calculateOverallDiskUsage(List<Map<String, Object>> diskMaps) {
        if (diskMaps == null || diskMaps.isEmpty()) {
            return 0;
        }
        for (Map<String, Object> disk : diskMaps) {
            Integer usagePercent = (Integer) disk.get("use_percent");
            if (usagePercent != null) {
                return usagePercent;
            }
        }
        return 0;
    }

    private String getDuration(String startTime) {
        try {
            LocalDateTime start = LocalDateTime.parse(startTime, LOG_TIME_FORMATTER);
            LocalDateTime end = LocalDateTime.now();
            long duration = java.time.Duration.between(start, end).toMillis();
            return duration + "ms";
        } catch (Exception e) {
            return "未知";
        }
    }
}
