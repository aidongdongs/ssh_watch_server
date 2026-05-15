package com.show.controller;

import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 监控数据控制器
 * 提供监控数据的JSON接口，用于前端动态渲染
 */
@RestController
@RequestMapping("/monitor")
public class MonitorDataController {

    private static final Logger log = LoggerFactory.getLogger(MonitorDataController.class);

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    /**
     * 获取所有监控数据，以JSON格式返回
     * 
     * @param sortBy 排序字段，可选值: cpuUsage, memoryUsage, diskUsagePercent, createdAt
     * @param order 排序顺序，可选值: asc, desc
     * @return 监控数据列表
     */
    @GetMapping("/list/data")
    public List<SystemInfo> getMonitorData(
            @RequestParam(defaultValue = "cpuUsage") String sortBy,
            @RequestParam(defaultValue = "desc") String order) {
        try {

            List<SystemInfo> monitors = systemInfoMapper.findAll();
            // 清除密码信息，避免泄露
            for (SystemInfo monitor : monitors) {
                monitor.setPassword(null);
            }
            
            // 根据参数进行排序
            if ("cpuUsage".equals(sortBy)) {
                if ("asc".equals(order)) {
                    monitors.sort(Comparator.comparing(SystemInfo::getCpuUsage, Comparator.nullsLast(Double::compareTo)));
                } else {
                    monitors.sort(Comparator.comparing(SystemInfo::getCpuUsage, Comparator.nullsLast(Double::compareTo)).reversed());
                }
            } else if ("memoryUsage".equals(sortBy)) {
                if ("asc".equals(order)) {
                    monitors.sort(Comparator.comparing(SystemInfo::getMemoryUsage, Comparator.nullsLast(String::compareTo)));
                } else {
                    monitors.sort(Comparator.comparing(SystemInfo::getMemoryUsage, Comparator.nullsLast(String::compareTo)).reversed());
                }
            } else if ("diskUsagePercent".equals(sortBy)) {
                if ("asc".equals(order)) {
                    monitors.sort(Comparator.comparing(SystemInfo::getDiskUsagePercent, Comparator.nullsLast(Integer::compareTo)));
                } else {
                    monitors.sort(Comparator.comparing(SystemInfo::getDiskUsagePercent, Comparator.nullsLast(Integer::compareTo)).reversed());
                }
            } else if ("createdAt".equals(sortBy)) {
                if ("asc".equals(order)) {
                    monitors.sort(Comparator.comparing(SystemInfo::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
                } else {
                    monitors.sort(Comparator.comparing(SystemInfo::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
                }
            }
            
            return monitors;
        } catch (Exception e) {
            log.error("获取监控数据时发生错误", e);
            return new ArrayList<>(); // 返回空列表
        }
    }
    
    /**
     * 模糊搜索主机（支持IP、主机名片段）
     *
     * @param host 搜索关键字
     * @return 匹配的监控数据列表
     */
    @GetMapping("/search")
    public List<SystemInfo> searchByHost(@RequestParam String host) {
        try {
            List<SystemInfo> monitors = systemInfoMapper.searchByHostLike(host);
            // 清除所有结果的密码信息
            for (SystemInfo monitor : monitors) {
                monitor.setPassword(null);
            }
            log.info("模糊搜索 [{}] 命中 {} 条记录", host, monitors.size());
            return monitors;
        } catch (Exception e) {
            log.error("模糊搜索主机时发生错误", e);
            return new ArrayList<>();
        }
    }
}