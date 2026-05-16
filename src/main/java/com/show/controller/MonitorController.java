package com.show.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 监控页面控制器
 * 页面数据由 MonitorDataController JSON 接口提供，前端 AJAX 渲染
 */
@Controller
public class MonitorController {

    @GetMapping("/monitor/list")
    public String listMonitors() {
        return "monitor/list";
    }
}