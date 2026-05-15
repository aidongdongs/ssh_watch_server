package com.show.task;

import com.show.entity.DiskUsage;
import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import com.show.util.SSHUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class MonitorTask {

    private static final Logger log = LoggerFactory.getLogger(MonitorTask.class);

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    /**
     * 采集单台服务器数据（在独立线程中执行）
     */
    private void collectSingleServer(SystemInfo server, String host, int port, String username, String password) {
        SSHUtil sshUtil = new SSHUtil();
        try {
            sshUtil.connect(host, port, username, password);

            if (sshUtil.isConnected()) {
                String topInfo = sshUtil.executeCommand("top -bn1", 15);
                String freeInfo = sshUtil.executeCommand("free -h", 10);
                String dfInfo = sshUtil.executeCommand("df -h", 10);

                Double cpuUsage = parseCpuUsage(topInfo);
                String memoryUsage = parseMemoryUsage(freeInfo);
                Integer diskUsagePercent = parseDiskUsage(dfInfo);

                server.setTopInfo(topInfo);
                server.setCpuUsage(cpuUsage);
                server.setFreeInfo(freeInfo);
                server.setMemoryUsage(memoryUsage);
                server.setDiskInfo(dfInfo);
                server.setDiskUsagePercent(diskUsagePercent);
                server.setCreatedAt(String.valueOf(LocalDateTime.now()));

                systemInfoMapper.updateSystemMonitorSelective(server);
                log.info("[线程 {}] 采集完成，CPU: {}%", host, cpuUsage);
            } else {
                log.error("[线程 {}] 无法连接", host);
                recordError(server, "SSH连接失败");
            }

        } catch (Exception e) {
            log.error("[线程 {}] 采集异常: {}", host, e.getMessage());
            recordError(server, "数据采集异常: " + e.getMessage());
        } finally {
            try {
                if (sshUtil.isConnected()) {
                    sshUtil.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void recordError(SystemInfo server, String errorMessage) {
        try {
            server.setTopInfo(errorMessage);
            server.setCpuUsage(0.0);
            server.setFreeInfo(errorMessage);
            server.setMemoryUsage("无法获取");
            server.setDiskInfo(errorMessage);
            server.setDiskUsagePercent(0);
            server.setCreatedAt(String.valueOf(LocalDateTime.now()));
            systemInfoMapper.updateSystemMonitorSelective(server);
        } catch (Exception e) {
            log.error("记录错误状态时出错: {}", e.getMessage());
        }
    }
    
    /**
     * 解析 top 命令输出，提取 CPU 使用率
     */
    private Double parseCpuUsage(String topOutput) {
        if (topOutput == null || topOutput.isEmpty()) {
            return 0.0;
        }
        
        try {
            // 匹配 Cpu(s):  0.0%us,  0.0%sy,  0.0%ni,100.0%id,  0.0%wa,  0.0%hi,  0.0%si,  0.0%st 格式
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("Cpu\\(s\\):\\s+([0-9.]+)%us");
            java.util.regex.Matcher matcher = pattern.matcher(topOutput);
            
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            } else {
                // 尝试匹配其他可能的格式
                pattern = java.util.regex.Pattern.compile("([0-9.]+)%?\\s+us");
                matcher = pattern.matcher(topOutput);
                if (matcher.find()) {
                    return Double.parseDouble(matcher.group(1));
                }
            }
            
            return 0.0;
        } catch (Exception e) {
            log.error("解析CPU使用率失败: {}", e.getMessage());
            return 0.0;
        }
    }
    
    /**
     * 解析 free 命令输出，提取内存使用情况
     */
    private String parseMemoryUsage(String freeOutput) {
        if (freeOutput == null || freeOutput.isEmpty()) {
            return "无法获取";
        }
        
        try {
            String[] lines = freeOutput.split("\n");
            for (String line : lines) {
                if (line.startsWith("Mem:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 7) {
                        long totalMem = Long.parseLong(parts[1]);
                        long usedMem = Long.parseLong(parts[2]);
                        double usagePercent = (double) usedMem / totalMem * 100;
                        return String.format("%.2f", usagePercent) + "%";
                    }
                }
            }
            
            return "无法获取";
        } catch (Exception e) {
            log.error("解析内存使用情况失败: {}", e.getMessage());
            return "无法获取";
        }
    }
    
    /**
     * 解析 df 命令输出，提取磁盘使用情况
     */
    private Integer parseDiskUsage(String dfOutput) {
        if (dfOutput == null || dfOutput.isEmpty()) {
            return 0;
        }
        
        try {
            String[] lines = dfOutput.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("([\\w/\\-0-9._]+)\\s+(\\d+[KMGTP]?)\\s+(\\d+[KMGTP]?)\\s+(\\d+[KMGTP]?)\\s+(\\d+)%\\s+(.+)");
                java.util.regex.Matcher matcher = pattern.matcher(line);
                
                if (matcher.matches()) {
                    return Integer.parseInt(matcher.group(5));
                }
            }
            
            return 0;
        } catch (Exception e) {
            log.error("解析磁盘使用情况失败: {}", e.getMessage());
            return 0;
        }
    }
}