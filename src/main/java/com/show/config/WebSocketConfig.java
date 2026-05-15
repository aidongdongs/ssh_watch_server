package com.show.config;

import com.show.mapper.SystemInfoMapper;
import com.show.websocket.SSHWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        log.info("注册WebSocket处理器: /ssh");
        registry.addHandler(sshWebSocketHandler(), "/ssh/**")
                .setAllowedOrigins("*");
        log.info("WebSocket处理器注册完成");
    }

    @Bean
    public SSHWebSocketHandler sshWebSocketHandler() {
        log.info("创建SSHWebSocketHandler Bean");
        SSHWebSocketHandler handler = new SSHWebSocketHandler();
        handler.setSystemInfoMapper(systemInfoMapper);
        return handler;
    }
}