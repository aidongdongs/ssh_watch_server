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

    @RequestMapping("/")
    public String shouye(Model model){
        // 增加逻辑，查询数据返回首页。
        log.info("shouye");
        List<SystemInfo> monitors = systemMonitorMapper.findAll();
        monitors.forEach(i -> log.info("{}", i));
        model.addAttribute("monitors", monitors);
        log.info("monitors count: {}", monitors.size());
        return "monitor/list";
    }

    @GetMapping("service/add")
    public String addSsh(Model model){

        return "sshpage/add-server";
    }
    
}