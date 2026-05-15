package com.show.websocket;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import org.springframework.web.socket.WebSocketSession;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public class SSHConnectionInfo {
    private final WebSocketSession webSocketSession;
    private final Session session;
    private final ChannelShell channel;
    private final InputStream input;
    private final OutputStream output;
    private final PrintStream commander;

    public SSHConnectionInfo(WebSocketSession webSocketSession, Session session, ChannelShell channel, OutputStream output, PrintStream commander) {
        this.webSocketSession = webSocketSession;
        this.session = session;
        this.channel = channel;
        this.input = null;
        this.output = output;
        this.commander = commander;
    }
    
    // 为兼容之前的构造函数添加的构造函数
    public SSHConnectionInfo(Session session, ChannelShell channel, InputStream input, OutputStream output) {
        this.webSocketSession = null;
        this.session = session;
        this.channel = channel;
        this.input = input;
        this.output = output;
        this.commander = null;
    }

    public WebSocketSession getWebSocketSession() {
        return webSocketSession;
    }

    public Session getSshSession() {
        return session;
    }

    public ChannelShell getChannel() {
        return channel;
    }

    public InputStream getInput() {
        return input;
    }

    public OutputStream getOutput() {
        return output;
    }
    
    public PrintStream getCommander() {
        return commander;
    }
}