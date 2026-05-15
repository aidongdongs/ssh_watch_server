package com.show.controller;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;

/**
 * 错误处理
 */
@Controller
public class CustomErrorController implements ErrorController {

    /**
     * 处理错误请求，根据不同状态码返回对应的错误页面
     *
     * @param request HTTP 请求对象，包含错误状态码属性
     * @return ModelAndView 包含错误信息和视图名称
     */
    @RequestMapping("/error")
    public ModelAndView handleError(HttpServletRequest request) {
        ModelAndView modelAndView = new ModelAndView();
        
        // 获取错误状态码
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        
        if (status != null) {
            Integer statusCode = Integer.valueOf(status.toString());
            
            // 根据不同的错误状态码返回不同的错误页面
            if (statusCode == HttpStatus.NOT_FOUND.value()) {
                modelAndView.setViewName("error");
                modelAndView.addObject("errorMessage", "页面未找到");
                modelAndView.addObject("errorDetails", "抱歉，您访问的页面不存在。");
            } else if (statusCode == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                modelAndView.setViewName("error");
                modelAndView.addObject("errorMessage", "服务器内部错误");
                modelAndView.addObject("errorDetails", "服务器发生了内部错误，请稍后重试。");
            } else {
                modelAndView.setViewName("error");
                modelAndView.addObject("errorMessage", "发生错误");
                modelAndView.addObject("errorDetails", "发生了未知错误。");
            }
        } else {
            modelAndView.setViewName("error");
            modelAndView.addObject("errorMessage", "发生错误");
            modelAndView.addObject("errorDetails", "发生了未知错误。");
        }
        
        return modelAndView;
    }


}