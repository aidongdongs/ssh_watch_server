package com.show.controller;

import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("/shell")
public class ShellController {

    private static final Logger log = LoggerFactory.getLogger(ShellController.class);

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    @GetMapping("/{id:\\d+}")
    public String openShell(@PathVariable Integer id, Model model) {
        log.info("尝试访问服务器ID: {}", id);
        SystemInfo server = systemInfoMapper.selectById(String.valueOf(id));
        if (server != null) {
            log.info("获取服务器信息: ID={}, Host={}, Port={}, Username={}",
                    server.getId(), server.getHost(), server.getPort(), server.getUsername());
            model.addAttribute("server", server);
            return "shell/console";
        } else {
            log.error("未找到ID为{}的服务器", id);
            // 检查数据库中所有服务器信息
            try {
                List<SystemInfo> allServers = systemInfoMapper.findAll();
                log.info("数据库中所有服务器:");
                for (SystemInfo s : allServers) {
                    log.info("ID: {}, Host: {}", s.getId(), s.getHost());
                }
            } catch (Exception e) {
                log.error("查询所有服务器时出错: {}", e.getMessage());
            }
            return "redirect:/monitor/list";
        }
    }

    @GetMapping("/console/{id}")
    public String console(@PathVariable Integer id, Model model) {
        log.info("尝试访问服务器ID: {}", id);
        SystemInfo server = systemInfoMapper.selectById(String.valueOf(id));
        if (server != null) {
            log.info("获取服务器信息: ID={}, Host={}, Port={}, Username={}",
                    server.getId(), server.getHost(), server.getPort(), server.getUsername());
            model.addAttribute("server", server);
            return "shell/console";
        } else {
            log.error("未找到ID为{}的服务器", id);
            // 检查数据库中所有服务器信息
            try {
                List<SystemInfo> allServers = systemInfoMapper.findAll();
                log.info("数据库中所有服务器:");
                for (SystemInfo s : allServers) {
                    log.info("ID: {}, Host: {}", s.getId(), s.getHost());
                }
            } catch (Exception e) {
                log.error("查询所有服务器时出错: {}", e.getMessage());
            }
            return "redirect:/monitor/list";
        }
    }
}