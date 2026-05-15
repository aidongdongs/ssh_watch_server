package com.show.sftp;

import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件浏览器 Controller
 * 页面路由 + JSON REST API
 */
@Controller
@RequestMapping("/sftp")
public class FileBrowserController {

    private static final Logger log = LoggerFactory.getLogger(FileBrowserController.class);

    @Autowired
    private SftpService sftpService;

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    /**
     * 文件浏览器页面
     */
    @GetMapping("/{serverId:\\d+}")
    public String browser(@PathVariable Long serverId, Model model) {
        SystemInfo server = systemInfoMapper.selectById(String.valueOf(serverId));
        if (server == null) {
            return "error/404";
        }
        server.setPassword(null); // 注入模板前清空密码
        model.addAttribute("server", server);
        return "sftp/browser";
    }

    /**
     * 列目录
     */
    @GetMapping("/api/{serverId}/list")
    @ResponseBody
    public Map<String, Object> list(@PathVariable Long serverId,
                                     @RequestParam(defaultValue = "/") String path) {
        try {
            List<FileItem> items = sftpService.listFiles(serverId, path);
            return ok(items);
        } catch (Exception e) {
            log.error("列目录失败", e);
            return fail(e.getMessage());
        }
    }

    /**
     * 下载文件（流式传输，不缓冲到内存）
     */
    @GetMapping("/api/{serverId}/download")
    public void download(@PathVariable Long serverId,
                         @RequestParam String path,
                         HttpServletResponse response) {
        try {
            path = SftpUtils.sanitizePath(path);
            String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            response.setContentType("application/octet-stream");
            try (OutputStream out = response.getOutputStream()) {
                sftpService.downloadFile(serverId, path, out);
                out.flush();
            }
        } catch (Exception e) {
            log.error("下载文件失败", e);
            try {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    /**
     * 删除文件或目录
     */
    @PostMapping("/api/{serverId}/delete")
    @ResponseBody
    public Map<String, Object> delete(@PathVariable Long serverId,
                                       @RequestBody Map<String, Object> body) {
        try {
            String path = (String) body.get("path");
            String type = (String) body.get("type");
            if (type == null) type = "file";
            sftpService.deleteFile(serverId, path, type);
            return ok(null);
        } catch (Exception e) {
            log.error("删除失败", e);
            return fail(e.getMessage());
        }
    }

    /**
     * 重命名
     */
    @PostMapping("/api/{serverId}/rename")
    @ResponseBody
    public Map<String, Object> rename(@PathVariable Long serverId,
                                       @RequestBody Map<String, Object> body) {
        try {
            String path = (String) body.get("path");
            String newName = (String) body.get("newName");
            sftpService.renameFile(serverId, path, newName);
            return ok(null);
        } catch (Exception e) {
            log.error("重命名失败", e);
            return fail(e.getMessage());
        }
    }

    /**
     * 新建文件夹
     */
    @PostMapping("/api/{serverId}/mkdir")
    @ResponseBody
    public Map<String, Object> mkdir(@PathVariable Long serverId,
                                      @RequestBody Map<String, Object> body) {
        try {
            String path = (String) body.get("path");
            sftpService.createDirectory(serverId, path);
            return ok(null);
        } catch (Exception e) {
            log.error("新建文件夹失败", e);
            return fail(e.getMessage());
        }
    }

    // ===== 统一响应工具方法 =====

    private Map<String, Object> ok(Object data) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", true);
        r.put("data", data);
        r.put("message", null);
        return r;
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("success", false);
        r.put("data", null);
        r.put("message", msg);
        return r;
    }
}
