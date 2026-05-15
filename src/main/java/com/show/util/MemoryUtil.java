package com.show.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内存使用率解析工具类，解析 free 命令输出，支持多 Linux 发行版格式
 */
public class MemoryUtil {

    /**
     * 从 free -b 输出中解析内存和 Swap 使用率
     * 针对CentOS、OpenEuler、Kylin等系统的兼容性优化
     * 返回 Map<String, Object>：
     * - "mem_usage_percent" -> Double
     * - "mem_total" -> Long (bytes)
     * - "mem_used" -> Long (bytes)
     * - "swap_usage_percent" -> Double
     * - "swap_total" -> Long (bytes)
     */
    public static Map<String, Object> parseMemoryUsage(String freeOutput) {
        Map<String, Object> result = new HashMap<>();

        if (freeOutput == null || freeOutput.trim().isEmpty()) {
            return result;
        }

        String memLine = null, swapLine = null;
        String[] lines = freeOutput.split("\n");

        for (String line : lines) {
            // 兼容不同系统的输出格式，如CentOS、OpenEuler、Kylin等
            if (line.startsWith("Mem:") || line.toLowerCase().startsWith("mem:")) memLine = line;
            if (line.startsWith("Swap:") || line.toLowerCase().startsWith("swap:")) swapLine = line;
        }

        if (memLine != null) {
            Map<String, Long> mem = parseMemoryLine(memLine);
            long total = mem.get("total");
            long used = mem.get("used");
            // 在某些系统上，使用量可能需要计算为 total - free - buffers - cache
            if (used == 0 && mem.containsKey("free")) {
                used = total - mem.get("free") - mem.get("buff_cache");
            }
            double usage = total > 0 ? (used * 100.0 / total) : 0.0;

            result.put("mem_usage_percent", usage);
            result.put("mem_total", total);
            result.put("mem_used", used);
            result.put("mem_free", mem.get("free"));
            result.put("mem_available", mem.get("available")); // 可能为0，但不会空指针
        }

        if (swapLine != null) {
            Map<String, Long> swap = parseMemoryLine(swapLine);
            long total = swap.get("total");
            long used = swap.get("used");
            double usage = total > 0 ? (used * 100.0 / total) : 0.0;

            result.put("swap_usage_percent", usage);
            result.put("swap_total", total);
            result.put("swap_used", used);
            result.put("swap_free", swap.get("free"));
        }

        return result;
    }
    
    /**
     * 解析内存行，兼容不同Linux发行版的输出格式
     */
    private static Map<String, Long> parseMemoryLine(String line) {
        Map<String, Long> map = new HashMap<>();

        // 先按空白符分割
        String[] parts = line.trim().split("\\s+");

        if (parts.length < 4) {
            throw new IllegalArgumentException("行格式无效: " + line);
        }

        // 第0个是 "Mem:" 或 "Swap:"

        // 至少包含 total, used, free
        map.put("total", parseSize(parts[1]));
        map.put("used", parseSize(parts[2]));
        map.put("free", parseSize(parts[3]));

        // 如果有 shared
        if (parts.length > 4) {
            map.put("shared", parseSize(parts[4]));
        } else {
            map.put("shared", 0L);
        }

        // 如果有 buff/cache
        if (parts.length > 5) {
            map.put("buff_cache", parseSize(parts[5]));
        } else {
            map.put("buff_cache", 0L);
        }

        // 如果有 available（通常是 Mem 行才有）
        if (parts.length > 6) {
            map.put("available", parseSize(parts[6]));
        } else {
            map.put("available", 0L);
        }

        return map;
    }
    
    /**
     * 解析大小字符串为字节数
     */
    private static long parseSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isEmpty()) return 0;

        sizeStr = sizeStr.trim();
        double value;
        String unit = "B"; // 默认单位是字节

        // 检查是否以字母结尾（带单位）
        Pattern pattern = Pattern.compile("^(\\d+(?:\\.\\d+)?)([KMGT]?)$", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sizeStr);

        if (matcher.matches()) {
            value = Double.parseDouble(matcher.group(1));
            String suffix = matcher.group(2).toUpperCase();
            if (!suffix.isEmpty()) {
                unit = suffix;
            }
        } else {
            // 尝试直接解析为数字
            try {
                return Long.parseLong(sizeStr);
            } catch (NumberFormatException e) {
                // 如果还是无法解析，则返回0
                return 0;
            }
        }

        switch (unit) {
            case "T": return (long) (value * 1024 * 1024 * 1024 * 1024);
            case "G": return (long) (value * 1024 * 1024 * 1024);
            case "M": return (long) (value * 1024 * 1024);
            case "K": return (long) (value * 1024);
            case "B":
            default:  return (long) value;
        }
    }

    /**
     * 格式化字节数为人类可读格式
     */
    public static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024 * 1024) return String.format("%.2fT", bytes / (1024.0 * 1024 * 1024 * 1024));
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2fG", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format("%.2fM", bytes / (1024.0 * 1024));
        if (bytes >= 1024L) return String.format("%.2fK", bytes / 1024.0);
        return bytes + "B";
    }
}