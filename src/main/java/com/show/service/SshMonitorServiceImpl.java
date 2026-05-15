package com.show.service;

import com.show.entity.CollectResult;
import com.show.entity.DiskUsage;
import com.show.entity.SystemInfo;
import com.show.mapper.DiskUsageMapper;
import com.show.mapper.SystemInfoMapper;
import com.show.task.MonitorCollectorTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
public class SshMonitorServiceImpl implements SshMonitorService {

    private static final Logger log = LoggerFactory.getLogger(SshMonitorServiceImpl.class);

    @Autowired
    SystemInfoMapper systemInfoMapper;

    @Autowired
    DiskUsageMapper diskUsageMapper;

    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void collectAllMonitors() {
        String startTime = LocalDateTime.now().format(LOG_TIME_FORMATTER);
        log.info("[{}] 开始采集所有服务器数据", startTime);

        List<SystemInfo> systemInfoList = systemInfoMapper.findAll();
        log.info("[{}] 共获取到 {} 台服务器需要采集",
                LocalDateTime.now().format(LOG_TIME_FORMATTER), systemInfoList.size());

        if (systemInfoList.isEmpty()) {
            log.info("[{}] 没有需要采集的服务器",
                    LocalDateTime.now().format(LOG_TIME_FORMATTER));
            return;
        }

        // 阶段1: 并行采集数据（SSH网络IO密集，多线程提升效率）
        int threadCount = Math.min(systemInfoList.size(), Runtime.getRuntime().availableProcessors() * 2);
        log.info("[{}] 创建线程池并行采集，线程数: {}", LocalDateTime.now().format(LOG_TIME_FORMATTER), threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<CollectResult>> futures = new ArrayList<>();

        for (SystemInfo systemInfo : systemInfoList) {
            MonitorCollectorTask task = new MonitorCollectorTask(systemInfo);
            futures.add(executor.submit(task));
        }

        executor.shutdown();

        // 等待所有采集任务完成
        List<CollectResult> results = new ArrayList<>();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                log.error("[{}] 部分任务执行超时！", LocalDateTime.now().format(LOG_TIME_FORMATTER));
                // 强制取消未完成的任务
                for (Future<CollectResult> future : futures) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
            }
            // 收集已完成的结果
            for (Future<CollectResult> future : futures) {
                if (future.isDone() && !future.isCancelled()) {
                    try {
                        results.add(future.get());
                    } catch (ExecutionException e) {
                        log.error("采集任务异常: {}", e.getCause().getMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            log.error("[{}] 采集过程中出现中断: {}", LocalDateTime.now().format(LOG_TIME_FORMATTER), e.getMessage());
            Thread.currentThread().interrupt();
            return;
        }

        log.info("[{}] 并行采集完成，共 {} 个结果，开始顺序写入数据库...",
                LocalDateTime.now().format(LOG_TIME_FORMATTER), results.size());

        // 阶段2: 顺序写入数据库（SQLite单写入，避免锁竞争）
        for (CollectResult result : results) {
            try {
                SystemInfo info = result.getSystemInfo();
                if (result.isError()) {
                    writeErrorToDb(info, result.getErrorMessage());
                } else {
                    writeSuccessToDb(info, result.getDiskUsages());
                }
            } catch (Exception e) {
                log.error("写入数据库失败: {}", e.getMessage());
            }
        }

        String endTime = LocalDateTime.now().format(LOG_TIME_FORMATTER);
        log.info("[{}] 所有服务器采集任务已完成，共写入 {} 条数据", endTime, results.size());
    }

    private void writeSuccessToDb(SystemInfo systemInfo, List<DiskUsage> diskUsages) {
        // 更新服务器监控数据
        systemInfoMapper.updateSystemMonitorSelective(systemInfo);

        // 插入磁盘分区数据（先删后插）
        if (systemInfo.getId() != null) {
            diskUsageMapper.deleteById(String.valueOf(systemInfo.getId()));

            if (diskUsages != null) {
                for (DiskUsage diskUsage : diskUsages) {
                    diskUsageMapper.insert(diskUsage);
                }
            }
        }
    }

    private void writeErrorToDb(SystemInfo systemInfo, String errorMessage) {
        systemInfo.setCpuUsage(0.0);
        systemInfo.setMemoryUsage("无法获取");
        systemInfo.setTopInfo(errorMessage);
        systemInfo.setFreeInfo(errorMessage);
        systemInfo.setDiskInfo(errorMessage);
        systemInfo.setCreatedAt(String.valueOf(LocalDateTime.now()));
        systemInfoMapper.updateSystemMonitorSelective(systemInfo);
    }
}
