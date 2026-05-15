package com.show.util;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class DiskUtil {

    /**
     * 从 df -hT 输出中解析真实磁盘分区使用率
     * 返回 List<Map<String, Object>>，每个 Map 包含：
     * - "filesystem"
     * - "type"
     * - "size"
     * - "used"
     * - "avail"
     * - "use_percent" (Integer)
     * - "mounted_on"
     */
    public static List<Map<String, Object>> parseDiskUsage(String dfOutput) {
        List<Map<String, Object>> result = new ArrayList<>();

        if (dfOutput == null || dfOutput.trim().isEmpty()) {
            return result;
        }

        String[] lines = dfOutput.split("\n");
        for (int i = 1; i < lines.length; i++) { // 跳过标题行
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            // 处理可能包含空格的文件系统名称
            String[] parts = line.split("\\s+");
            if (parts.length < 6) continue;

            int n = parts.length;
            String mountedOn = parts[n - 1];
            String usePercentStr = parts[n - 2];
            String avail = parts[n - 3];
            String used = parts[n - 4];
            String size = parts[n - 5];

            // df -hT (7 列): filesystem type size used avail use% mounted
            // df -h  (6 列): filesystem size used avail use% mounted
            String filesystem;
            String type;
            if (n >= 7) {
                type = parts[n - 6];
                filesystem = String.join(" ", Arrays.copyOfRange(parts, 0, n - 6));
            } else {
                type = "";
                filesystem = String.join(" ", Arrays.copyOfRange(parts, 0, n - 5));
            }

            // 过滤虚拟文件系统
            if (isVirtualFilesystem(filesystem, type)) {
                continue;
            }

            int usePercent;
            try {
                usePercent = Integer.parseInt(usePercentStr.replace("%", "").trim());
            } catch (NumberFormatException e) {
                continue;
            }

            Map<String, Object> disk = new HashMap<>();
            disk.put("filesystem", filesystem);
            disk.put("type", type);
            disk.put("size", size);
            disk.put("used", used);
            disk.put("avail", avail);
            disk.put("use_percent", usePercent);
            disk.put("mounted_on", mountedOn);

            result.add(disk);
        }

        return result;
    }

    /**
     * 判断是否为虚拟文件系统
     * 针对CentOS、OpenEuler、Kylin等系统的兼容性优化
     * 不再严格过滤非/dev/开头的设备，因为云环境和容器环境中的真实设备可能不以/dev/开头
     */
    private static boolean isVirtualFilesystem(String filesystem, String type) {
        Set<String> virtualTypes = Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList("tmpfs", "devtmpfs", "sysfs", "proc", "cgroup", "overlay", "fuse", "debugfs", "tracefs", "rpc_pipefs", "rpc_pipe", "selinuxfs", "autofs", "rpc_pipefs", "none"))
        );

        Set<String> virtualPrefixes = Collections.unmodifiableSet(
                new HashSet<>(Arrays.asList("tmpfs", "devtmpfs", "udev", "shm", "run", "sys", "proc", "cgroup", "overlay", "none"))
        );

        // 检查文件系统类型
        if (virtualTypes.contains(type.toLowerCase())) return true;

        // 检查文件系统名称前缀
        for (String prefix : virtualPrefixes) {
            if (filesystem.toLowerCase().startsWith(prefix)) {
                return true;
            }
        }

        // 特殊处理：某些系统上的设备映射器
        if (filesystem.startsWith("/dev/mapper/") && (filesystem.contains("swap") || filesystem.contains("_swap"))) {
            return true;
        }

        // 特殊处理：某些系统上的LVM设备
        if (filesystem.startsWith("/dev/dm-")) {
            return true;
        }

        return false;
    }
}