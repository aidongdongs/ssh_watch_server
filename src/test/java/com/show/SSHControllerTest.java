package com.show;

import com.jcraft.jsch.JSchException;
import com.show.entity.DiskUsage;
import com.show.entity.SystemInfo;
import com.show.mapper.DiskUsageMapper;
import com.show.mapper.SystemInfoMapper;
import com.show.util.DiskUtil;
import com.show.util.MemoryUtil;
import com.show.util.SSHUtil;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SpringBootTest
public class SSHControllerTest {

    private static final Logger log = LoggerFactory.getLogger(SSHControllerTest.class);

    SSHUtil sshUtil = new SSHUtil();

    @Autowired
    SystemInfoMapper systemInfoMapper;

    @Autowired
    DiskUsageMapper diskUsageMapper;
//    @Test
//    public void test1(){
//
//        SystemInfo benji= new SystemInfo("8.138.104.239",22,"root","Aidong@1010");
////        SystemInfo yunfuw= new SystemInfo("8.138.104.239",22,"root","Aidong@1010");
//
//        log.info("{}", getSystemInfo(benji));
////        System.out.println(getSystemInfo(yunfuw));
//    }


//    public SystemInfo getSystemInfo(SystemInfo systemInfo){
//        SSHUtil sshUtil = new SSHUtil();
//        log.info("开始测试");
//        try {
////            sshUtil.connect("8.138.104.239",22,"root","Aidong@1010");
//            sshUtil.connect(systemInfo.getHost(),systemInfo.getPort(),systemInfo.getUsername(),systemInfo.getPassword());
//            String men = sshUtil.executeCommand("free -b");
//            String menInfo = sshUtil.executeCommand("free -h");
//            systemInfo.setFreeInfo(menInfo);
//
//
//            Map<String, Object> memInfo = MemoryUtil.parseMemoryUsage(men);
//            // ✅ 格式化输出
//            if (memInfo.containsKey("mem_total")) {
//                Long total = (Long) memInfo.get("mem_total");
//                Long used = (Long) memInfo.get("mem_used");
//                Double usagePercent = (Double) memInfo.get("mem_usage_percent");
////                String free = "📊 内存总量: " + MemoryUtil.formatBytes(total)+"  "+ "📈 已使用量: " + MemoryUtil.formatBytes(used)+" "+ "UsageId 内存使用率: " + String.format("%.2f%%", usagePercent);
//                String    free =  MemoryUtil.formatBytes(total)+"/" + MemoryUtil.formatBytes(used)+" "+ " 使用率: " + String.format("%.2f%%", usagePercent);
//                systemInfo.setMemoryUsage( free);
//
//            }
//
//
//            String dis = sshUtil.executeCommand("df -HT");
//            systemInfo.setDiskInfo(dis);
//
//            List<Map<String, Object>> maps = DiskUtil.parseDiskUsage(dis);
//
//            List<DiskUsage> diskUsages = new ArrayList<>();
//
//
//
//            String top  = sshUtil.executeCommand("top -n 1 -b ");
//            double cpuUsage = sshUtil.parseCpuUsageFromTop(top);
//            systemInfo.setTopInfo(top);
//            systemInfo.setCpuUsage(cpuUsage);
//
//
//            diskUsages.forEach(i -> log.info("{}", i));
//            systemInfo.setCreatedAt(String.valueOf(LocalDateTime.now()));
//            systemInfo.setId(12L);
//            log.info("{}", systemInfo);
//            systemInfoMapper.updateSystemMonitorSelective(systemInfo);
//
//
//            for (Map<String, Object> map : maps) {
//                DiskUsage disk = new DiskUsage();
//                disk.setFilesystem((String) map.get("filesystem"));
//                disk.setType((String) map.get("type"));
//                disk.setMountedOn((String) map.get("mounted_on"));
//                disk.setSize((String) map.get("size"));
//                disk.setUsed((String) map.get("used"));
//                disk.setAvail((String) map.get("avail"));
//                disk.setUsagePercent((Integer) map.get("use_percent"));
//                if (systemInfo.getId()!=null){
//                    disk.setMonitorId(systemInfo.getId());
//                }
//                log.info("{} 插入数据成功，获取自增的id为获取到，导致关联的磁盘分区数据插入失败", systemInfo.getHost());
//                diskUsageMapper.insert(disk);
//            }
//            systemInfo.setDiskUsages(diskUsages);
//
//            sshUtil.disconnect();
//        } catch (JSchException e) {
//            log.error("建立连接出现问题: {}", e.getMessage());
//            throw new RuntimeException(e);
//        } catch (IOException e) {
//            log.error("执行命令出现问题");
//            throw new RuntimeException(e);
//        } finally {
//            if (sshUtil.isConnected()){
//                sshUtil.disconnect();
//            }
//
//        }
//        log.info("结束测试");
//        return  systemInfo;
//    }
//}
}
