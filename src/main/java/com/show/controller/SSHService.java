package com.show.controller;


import com.show.entity.DiskUsage;
import com.show.entity.SystemInfo;
import com.show.mapper.DiskUsageMapper;
import com.show.mapper.SystemInfoMapper;
import com.show.service.SshMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/ssh-service")
public class SSHService {

    private static final Logger log = LoggerFactory.getLogger(SSHService.class);

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    @Autowired
    private DiskUsageMapper diskUsageMapper;

    @Autowired
    private SshMonitorService sshMonitorService;

    /**
     * 解析 top 命令输出，提取 CPU 使用率
     * @param topOutput top 命令的输出
     * @return CPU 使用率百分比
     */
    public Double parseCpuUsage(String topOutput) {
        if (topOutput == null || topOutput.isEmpty()) {
            log.error("topOutput 为空，无法解析 CPU 使用率");
            return 0.0;
        }

        try {
            Pattern pattern = Pattern.compile("Cpu\\(s\\):\\s+([0-9.]+)%us");
            Matcher matcher = pattern.matcher(topOutput);

            if (matcher.find()) {
                double usCpu = Double.parseDouble(matcher.group(1));
                log.info("解析到用户态 CPU 使用率: {}%", usCpu);
                return usCpu;
            } else {
                pattern = Pattern.compile("([0-9.]+)%?\\s+us");
                matcher = pattern.matcher(topOutput);
                if (matcher.find()) {
                    double usCpu = Double.parseDouble(matcher.group(1));
                    log.info("解析到用户态 CPU 使用率: {}%", usCpu);
                    return usCpu;
                }
            }

            log.error("未能从 top 输出中解析到 CPU 使用率");
            return 0.0;
        } catch (Exception e) {
            log.error("解析 CPU 使用率时出错", e);
            return 0.0;
        }
    }

    /**
     * 解析 free 命令输出，提取内存使用情况
     * @param freeOutput free 命令的输出
     * @return 内存使用率字符串
     */
    public String parseMemoryUsage(String freeOutput) {
        if (freeOutput == null || freeOutput.isEmpty()) {
            log.error("freeOutput 为空，无法解析内存使用情况");
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
                        String usageStr = String.format("%.2f", usagePercent) + "%";
                        log.info("解析到内存使用率: {}", usageStr);
                        return usageStr;
                    }
                }
            }

            log.error("未能从 free 输出中解析到内存使用情况");
            return "无法获取";
        } catch (Exception e) {
            log.error("解析内存使用情况时出错", e);
            return "无法获取";
        }
    }

    /**
     * 解析 df 命令输出，提取磁盘使用情况
     * @param dfOutput df 命令的输出
     * @return 磁盘使用情况列表
     */
    public List<DiskUsage> parseDiskUsage(String dfOutput) {
        List<DiskUsage> diskUsages = new ArrayList<>();
        if (dfOutput == null || dfOutput.isEmpty()) {
            log.error("dfOutput 为空，无法解析磁盘使用情况");
            return diskUsages;
        }

        try {
            String[] lines = dfOutput.split("\n");

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;

                Pattern pattern = Pattern.compile("([\\w/\\-0-9._]+)\\s+(\\d+[KMGTP]?)\\s+(\\d+[KMGTP]?)\\s+(\\d+[KMGTP]?)\\s+(\\d+)%\\s+(.+)");
                Matcher matcher = pattern.matcher(line);

                if (matcher.matches()) {
                    DiskUsage diskUsage = new DiskUsage();
                    diskUsage.setFilesystem(matcher.group(1));
                    diskUsage.setSize(matcher.group(2));
                    diskUsage.setUsed(matcher.group(3));
                    diskUsage.setAvail(matcher.group(4));
                    diskUsage.setUsagePercent(Integer.parseInt(matcher.group(5)));
                    diskUsage.setMountedOn(matcher.group(6));

                    // 设置创建时间 - 修复 LocalDateTime 使用问题
                    diskUsage.setCreatedAt(String.valueOf(LocalDateTime.now()));

                    diskUsages.add(diskUsage);
                    log.info("解析到磁盘使用情况: {} {}%", diskUsage.getFilesystem(), diskUsage.getUsagePercent());
                } else {
                    log.info("跳过无法解析的行: {}", line);
                }
            }

            if (diskUsages.isEmpty()) {
                log.error("未能从 df 输出中解析到磁盘使用情况");
            }

            return diskUsages;
        } catch (Exception e) {
            log.error("解析磁盘使用情况时出错", e);
            return diskUsages;
        }
    }

    @RequestMapping("/edit")
    public String edit(String username, String password, Integer port, String host, String id, Model model) {
        if (id == null || id.trim().isEmpty()) {
            List<SystemInfo> monitors = systemInfoMapper.findAll();
            model.addAttribute("monitors", monitors);
            return "monitor/list";
        }

        SystemInfo systemInfo = new SystemInfo();
        systemInfo.setId(Long.valueOf(id));
        systemInfo.setUsername(username);
        systemInfo.setPassword(password);
        systemInfo.setPort(port);
        systemInfo.setHost(host);
        log.info("更新服务器信息: {}", systemInfo);
        systemInfoMapper.updateSystemMonitorSelective(systemInfo);

        List<SystemInfo> monitors = systemInfoMapper.findAll();
        model.addAttribute("monitors", monitors);
        return "monitor/list";
    }

    @RequestMapping("/select/edit/{id}")
    public String editSelect(@PathVariable("id") String id, Model model) {
        log.info("编辑选择服务器ID: {}", id);
        if (id == null || id.trim().isEmpty()) {
            List<SystemInfo> monitors = systemInfoMapper.findAll();
            model.addAttribute("monitors", monitors);
            return "monitor/list";
        }
        
        SystemInfo systemInfo = systemInfoMapper.selectById(id);
        if (systemInfo == null) {
            List<SystemInfo> monitors = systemInfoMapper.findAll();
            model.addAttribute("monitors", monitors);
            return "monitor/list";
        }

        model.addAttribute("server", systemInfo);
        return "sshpage/edit";
    }

    @RequestMapping("/add")
    public String add(String username, String password, Integer port, String host, Model model) {
        List<SystemInfo> monitors = systemInfoMapper.findAll();
        model.addAttribute("monitors", monitors);

        if (host == null || host.trim().isEmpty()) {
            log.error("主机地址不能为空");
            return "monitor/list";
        }

        // 检查是否已存在相同主机
        List<SystemInfo> existing = new ArrayList<>();
        SystemInfo existingSystemInfo = systemInfoMapper.selectByHost(host);
        if (existingSystemInfo != null) {
            existing.add(existingSystemInfo);
        }
        if (existing != null && !existing.isEmpty()) {
            log.warn("主机 {} 已存在", host);
            return "monitor/list";
        }

        SystemInfo systemInfo = new SystemInfo();
        systemInfo.setUsername(username);
        systemInfo.setPassword(password);
        systemInfo.setHost(host);
        systemInfo.setPort(port != null ? port : 22); // 默认 SSH 端口
        systemInfo.setCreatedAt(String.valueOf(LocalDateTime.now())); // 设置创建时间
        
        Integer result = systemInfoMapper.insertSystemMonitor(systemInfo);
        log.info("插入结果: {}", result);

        monitors = systemInfoMapper.findAll();
        model.addAttribute("monitors", monitors);
        return "monitor/list";
    }

    @GetMapping("/delete/{id}")
    public String delete(@PathVariable("id") String id, Model model) {
        if (id == null || id.trim().isEmpty()) {
            log.error("ID 不能为空");
            List<SystemInfo> monitors = systemInfoMapper.findAll();
            model.addAttribute("monitors", monitors);
            return "monitor/list";
        }

        // 删除主记录
        if (systemInfoMapper.deleteById(String.valueOf(id)) != 1) {
            log.warn("删除id: {} 失败", id);
        }

        // 删除关联的磁盘使用记录
        if (diskUsageMapper.deleteById(id) <= 0) {
            log.warn("删除id: {} 的关联库失败", id);
        }
        
        List<SystemInfo> monitors = systemInfoMapper.findAll();
        model.addAttribute("monitors", monitors);
        return "monitor/list";
    }

    @PostMapping("/refresh/all")
    @ResponseBody
    public ResponseEntity<String> refreshAllMonitors() {
        try {
            sshMonitorService.collectAllMonitors();
            return ResponseEntity.ok("数据采集完成");
        } catch (Exception e) {
            return ResponseEntity.status(500).body("采集失败: " + e.getMessage());
        }
    }
}
