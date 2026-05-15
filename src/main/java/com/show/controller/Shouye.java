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
public class Shouye {

    private static final Logger log = LoggerFactory.getLogger(Shouye.class);

    @Autowired
    private SystemInfoMapper systemMonitorMapper;

    // 首页：查询所有监控数据并渲染到 monitor/list 模板
    // 进入 / 目录，进行跳转到对应的首页
    @RequestMapping("/")
    public String shouye(Model model){
        log.info("/ 跳转到首页");
         //  查询服务器数据
        List<SystemInfo> monitors = systemMonitorMapper.findAll();

        monitors.forEach(i -> log.info("{}", i));
        model.addAttribute("monitors", monitors);
        log.info("monitors count: {}", monitors.size());
        return "monitor/list";
    }

    // 添加服务器页面（渲染 sshpage/add-server.html 表单）
    @GetMapping("service/add")
    public String addSsh() {
        return "sshpage/add-server";
    }

}