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

    // 单个 SSH 命令超时（秒）
    private static final int COMMAND_TIMEOUT = 15;

    public MonitorCollectorTask(SystemInfo systemInfo) {
        this.systemInfo = systemInfo;
    }

    @Override
    public CollectResult call() {
        String startTime = LocalDateTime.now().format(LOG_TIME_FORMATTER);
        log.info("[{}] 开始采集服务器: {}:{}", startTime, systemInfo.getHost(), systemInfo.getPort());

        SSHUtil sshUtil = new SSHUtil();
        List<DiskUsage> diskUsages = new ArrayList<>();

        try {
            sshUtil.connect(systemInfo.getHost(), systemInfo.getPort(), systemInfo.getUsername(), systemInfo.getPassword());

            // 采集内存信息
            collectMemory(sshUtil);

            // 采集磁盘信息
            collectDisk(sshUtil, diskUsages);

            // 采集 CPU 信息
            collectCpu(sshUtil);

            systemInfo.setCreatedAt(LocalDateTime.now().format(LOG_TIME_FORMATTER));

            String endTime = LocalDateTime.now().format(LOG_TIME_FORMATTER);
            log.info("[{}] 服务器采集完成: {}:{} (耗时: {})", endTime, systemInfo.getHost(), systemInfo.getPort(), getDuration(startTime));

            return new CollectResult(systemInfo, diskUsages);

        } catch (JSchException e) {
            log.error("SSH连接失败: {}", e.getMessage());
            return new CollectResult(systemInfo, "SSH连接失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("未知错误", e);
            return new CollectResult(systemInfo, "未知错误: " + e.getMessage());
        } finally {
            if (sshUtil.isConnected()) {
                sshUtil.disconnect();
            }
        }
    }

    private void collectMemory(SSHUtil sshUtil) {
        try {
            String memInfo = sshUtil.executeCommand("free -h", COMMAND_TIMEOUT);
            systemInfo.setFreeInfo(memInfo);

            String memBytes = sshUtil.executeCommand("free -b", COMMAND_TIMEOUT);
            Map<String, Object> memInfoMap = MemoryUtil.parseMemoryUsage(memBytes);
            if (memInfoMap.containsKey("mem_total")) {
                Long total = (Long) memInfoMap.get("mem_total");
                Long used = (Long) memInfoMap.get("mem_used");
                Double usagePercent = (Double) memInfoMap.get("mem_usage_percent");
                systemInfo.setMemoryUsage(MemoryUtil.formatBytes(total) + "/" + MemoryUtil.formatBytes(used)
                        + " 使用率: " + String.format("%.2f%%", usagePercent));
            }
        } catch (Exception e) {
            log.warn("内存信息采集失败: {}", e.getMessage());
            systemInfo.setFreeInfo("内存采集失败: " + e.getMessage());
        }
    }

    private void collectDisk(SSHUtil sshUtil, List<DiskUsage> diskUsages) {
        try {
            String disk = sshUtil.executeCommand("df -hT", COMMAND_TIMEOUT);
            systemInfo.setDiskInfo(disk);

            List<Map<String, Object>> diskMaps = DiskUtil.parseDiskUsage(disk);
            systemInfo.setDiskUsagePercent(calculateMaxDiskUsage(diskMaps));

            for (Map<String, Object> map : diskMaps) {
                DiskUsage du = new DiskUsage();
                du.setFilesystem((String) map.get("filesystem"));
                du.setType((String) map.get("type"));
                du.setMountedOn((String) map.get("mounted_on"));
                du.setSize((String) map.get("size"));
                du.setUsed((String) map.get("used"));
                du.setAvail((String) map.get("avail"));
                du.setUsagePercent((Integer) map.get("use_percent"));
                du.setMonitorId(systemInfo.getId());
                du.setCreatedAt(LocalDateTime.now().format(LOG_TIME_FORMATTER));
                diskUsages.add(du);
            }
        } catch (Exception e) {
            log.warn("磁盘信息采集失败: {}", e.getMessage());
            systemInfo.setDiskInfo("磁盘采集失败: " + e.getMessage());
        }
    }

    private void collectCpu(SSHUtil sshUtil) {
        try {
            String top = sshUtil.executeCommand("top -bn 1", COMMAND_TIMEOUT);
            double cpuUsage = sshUtil.parseCpuUsageFromTop(top);
            systemInfo.setTopInfo(top);
            systemInfo.setCpuUsage(cpuUsage);
            // 从 top 输出中提取 CPU 使用率前三的进程信息，用于页面展示
            systemInfo.setTopProcesses(parseTopProcesses(top));
            // 从 top 输出中提取内存使用率前三的进程信息，用于页面展示
            systemInfo.setTopMemProcesses(parseTopMemProcesses(top));
        } catch (Exception e) {
            log.warn("CPU信息采集失败: {}", e.getMessage());
            systemInfo.setCpuUsage(0.0);
            systemInfo.setTopInfo("CPU采集失败: " + e.getMessage());
            systemInfo.setTopProcesses("解析失败");
            systemInfo.setTopMemProcesses("解析失败");
        }
    }

    // 取所有磁盘分区中的最大使用率
    private Integer calculateMaxDiskUsage(List<Map<String, Object>> diskMaps) {
        if (diskMaps == null || diskMaps.isEmpty()) {
            return 0;
        }
        int max = 0;
        for (Map<String, Object> disk : diskMaps) {
            Integer usagePercent = (Integer) disk.get("use_percent");
            if (usagePercent != null && usagePercent > max) {
                max = usagePercent;
            }
        }
        return max;
    }

    /**
     * 从 top -bn 1 输出中解析 CPU 使用率前三的进程
     * 委托给通用解析方法 parseTopByColumn，按 %CPU 列排序
     */
    private String parseTopProcesses(String topOutput) {
        return parseTopByColumn(topOutput, "%CPU");
    }

    /**
     * 从 top -bn 1 输出中解析内存使用率前三的进程
     * 委托给通用解析方法 parseTopByColumn，按 %MEM 列排序
     */
    private String parseTopMemProcesses(String topOutput) {
        return parseTopByColumn(topOutput, "%MEM");
    }

    /**
     * 通用 top 进程解析方法
     * <p>
     * top 输出的进程列表格式（各列以空格分隔）:
     *   PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ COMMAND
     * 通过定位列标题行确定排序列和 COMMAND 的列位置，兼容不同 Linux 发行版的 top 输出差异
     *
     * @param topOutput   top -bn 1 的原始输出
     * @param sortHeader  排序列名，如 "%CPU" 或 "%MEM"
     * @return 格式化字符串，每行包含 "PID %值% COMMAND"，最多三行。解析失败时返回中文错误描述
     */
    private String parseTopByColumn(String topOutput, String sortHeader) {
        if (topOutput == null || topOutput.isEmpty()) {
            return "暂无数据";
        }

        String[] lines = topOutput.split("\\r?\\n");

        // 找到进程列表的列标题行（以空格开头并包含 PID）
        int headerIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("^\\s+PID\\s+.*")) {
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0) return "无法解析进程列表";

        // 从标题行中确定排序列和 COMMAND 的列位置
        String[] headers = lines[headerIdx].trim().split("\\s+");
        int sortCol = -1;
        int cmdCol = -1;
        for (int i = 0; i < headers.length; i++) {
            if (sortHeader.equals(headers[i])) {
                sortCol = i;
            }
            if ("COMMAND".equals(headers[i])) {
                cmdCol = i;
            }
        }
        if (sortCol < 0) return "无法解析" + sortHeader + "列";
        if (cmdCol < 0) cmdCol = sortCol + 3; // 通常 COMMAND 在 %CPU 后第3列

        // 解析进程行
        List<Object[]> processes = new ArrayList<>(); // [pid, value, command]
        for (int i = headerIdx + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            // 跳过汇总行（以字母开头的行，如 "Tasks:", "%Cpu(s):" 等）
            if (line.matches("^[A-Za-z].*")) continue;

            String[] parts = line.split("\\s+");
            if (parts.length <= sortCol) continue;

            try {
                double value = Double.parseDouble(parts[sortCol]);

                // 提取命令名（COMMAND 列及之后的内容）
                StringBuilder cmd = new StringBuilder();
                for (int j = cmdCol; j < parts.length; j++) {
                    if (cmd.length() > 0) cmd.append(" ");
                    cmd.append(parts[j]);
                }

                processes.add(new Object[]{parts[0], value, cmd.toString()});
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的行
            }
        }

        // 按指标值降序排列，取前三
        processes.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));

        if (processes.isEmpty()) return "无进程数据";

        StringBuilder sb = new StringBuilder();
        int count = Math.min(3, processes.size());
        for (int i = 0; i < count; i++) {
            Object[] p = processes.get(i);
            if (sb.length() > 0) sb.append("\n");
            sb.append(String.format("%s  %.1f%%  %s", p[0], p[1], p[2]));
        }
        return sb.toString();
    }

    private String getDuration(String startTime) {
        try {
            LocalDateTime start = LocalDateTime.parse(startTime, LOG_TIME_FORMATTER);
            long duration = java.time.Duration.between(start, LocalDateTime.now()).toMillis();
            return duration + "ms";
        } catch (Exception e) {
            return "未知";
        }
    }
}