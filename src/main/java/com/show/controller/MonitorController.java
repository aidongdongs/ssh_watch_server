package com.show.controller;


import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    @Autowired
    private SystemInfoMapper systemMonitorMapper;

    // 页面监控数据接口
    @GetMapping("/monitor/list")
    public String listMonitors(Model model) {
        List<SystemInfo> monitors = systemMonitorMapper.findAll();
        monitors.forEach(i -> log.info("{}", i));
        model.addAttribute("monitors", monitors);
        return "monitor/list"; // 返回 templates/monitor/list.html
    }
}