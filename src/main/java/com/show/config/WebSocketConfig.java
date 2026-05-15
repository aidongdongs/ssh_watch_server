package com.show.config;

import com.show.sftp.SftpUploadWebSocketHandler;
import com.show.websocket.SSHWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;

/**
 * WebSocket 配置类，注册 SSH 终端和 SFTP 上传的 WebSocket 处理器
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Autowired
    private SSHWebSocketHandler sshWebSocketHandler;

    @Autowired
    private SftpUploadWebSocketHandler sftpUploadWebSocketHandler;

    /**
     * 注册 WebSocket 处理器路由
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        log.info("注册WebSocket处理器: /ssh");
        registry.addHandler(sshWebSocketHandler, "/ssh/**")
                .setAllowedOrigins("*");

        log.info("注册WebSocket处理器: /sftp/upload");
        registry.addHandler(sftpUploadWebSocketHandler, "/sftp/upload/{serverId}")
                .setAllowedOrigins("*")
                .addInterceptors(new SftpUploadInterceptor());

        log.info("WebSocket处理器注册完成");
    }

    /**
     * WebSocket 握手拦截器：从 URI 中提取 serverId 注入 session attributes
     */
    public static class SftpUploadInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest) {
                String path = ((ServletServerHttpRequest) request).getURI().getPath();
                String serverId = path.substring(path.lastIndexOf('/') + 1);
                attributes.put("serverId", serverId);
            }
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {}
    }

    /**
     * 配置 WebSocket 缓冲区大小（分块上传每块 512KB）
     */
    @Bean
    public ServletServerContainerFactoryBean websocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        container.setMaxTextMessageBufferSize(64 * 1024);
        return container;
    }
}