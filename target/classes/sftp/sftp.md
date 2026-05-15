1# SFTP 文件浏览管理器 — 功能规划

---

## 设计原则

> 整个 SFTP 功能前后端代码集中存放，与项目现有 `com.show.controller`、`com.show.service` 等包完全解耦。唯一的外部依赖是通过 `SystemInfoMapper.selectById()` 获取服务器连接信息。

---

## 新增目录结构

### 后端：`com.show.sftp` 包

```
src/main/java/com/show/sftp/
├── FileBrowserController.java        # Thymeleaf 页面路由 + JSON REST API
├── SftpService.java                  # SFTP 业务服务（核心逻辑）
├── FileItem.java                     # 文件条目 DTO
├── SftpSessionManager.java           # SFTP 连接会话管理
├── SftpUtils.java                    # 共享工具类（sanitizePath + ObjectMapper 单例）
├── SftpUploadWebSocketHandler.java   # WebSocket 分块上传处理器
└── UploadSession.java                # 上传会话状态（进度、流、连接）
```

### 前端文件

```
src/main/resources/
├── sftp/
│   └── sftp.md                       # 本规划文档（设计文档，不参与运行）
├── templates/sftp/
│   └── browser.html                  # Thymeleaf 模板
└── static/sftp/
    ├── browser.js                    # 前端交互逻辑
    └── browser.css                   # 样式
```

---

## 各文件职责

| 文件 | 职责 | 与现有代码的依赖 |
|------|------|-----------------|
| `FileItem.java` | 文件/目录条目 DTO：name, path, size, isDir, permissions, lastModified | 无 |
| `SftpSessionManager.java` | 管理 JSch SFTP 会话生命周期（连接/断开/重连）；从 DB 查询服务器凭证 | 依赖 `SystemInfoMapper` |
| `SftpService.java` | 所有 SFTP 业务操作：list, download, delete, rename, mkdir（上传由 WebSocket 独立处理） | 依赖 `SftpSessionManager` |
| `FileBrowserController.java` | 页面路由 `/sftp/{id}` + JSON API `/sftp/api/{id}/xxx`（不含上传） | 依赖 `SftpService` |
| `SftpUtils.java` | 共享工具类：`sanitizePath()` 路径校验 + `OBJECT_MAPPER` JSON 序列化单例 | 无 |
| `SftpUploadWebSocketHandler.java` | WebSocket 分块上传处理器：控制信号 + 二进制块写入 SFTP 流 | 依赖 `SystemInfoMapper`（查连接信息） |
| `UploadSession.java` | 上传会话状态对象：FileItem + OutputStream + 进度跟踪 | 无 |

| 前端文件 | 职责 |
|----------|------|
| `templates/sftp/browser.html` | Thymeleaf 模板，渲染页面框架 + 注入服务器信息 |
| `static/sftp/browser.js` | 目录列取、导航、上传下载、删除重命名新建文件夹等所有交互 |
| `static/sftp/browser.css` | 文件浏览器布局和样式 |

---

## 现有文件的修改

**需要修改 2 处：**

**① `WebSocketConfig.java`** — 注册 Upload WebSocket 路由 + 配置缓冲区 + HandshakeInterceptor：

```java
@Autowired
private SftpUploadWebSocketHandler sftpUploadWebSocketHandler;

@Override
public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    // 已有的 SSH Shell 路由
    registry.addHandler(sshWebSocketHandler, "/ssh/**").setAllowedOrigins("*");

    // 新增 SFTP 上传路由（通过 HandshakeInterceptor 提取 URI 中的 serverId）
    registry.addHandler(sftpUploadWebSocketHandler, "/sftp/upload/{serverId}")
            .setAllowedOrigins("*")
            .addInterceptors(new SftpUploadInterceptor());
}

/**
 * WebSocket 握手拦截器：从 URI 模板变量 {serverId} 提取服务器 ID，
 * 存入 session attributes，供 SftpUploadWebSocketHandler 后续使用。
 */
public static class SftpUploadInterceptor implements HandshakeInterceptor {
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest) {
            String path = ((ServletServerHttpRequest) request).getURI().getPath();
            // path like /sftp/upload/123
            String serverId = path.substring(path.lastIndexOf('/') + 1);
            attributes.put("serverId", serverId);
        }
        return true;
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler wsHandler, Exception exception) {}
}

/**
 * 配置 WebSocket 二进制缓冲区大小
 * 默认 Tomcat 只有 8KB，分块上传每块 512KB，必须调大
 */
@Bean
public ServletServerContainerFactoryBean websocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxBinaryMessageBufferSize(1024 * 1024); // 1MB
    container.setMaxTextMessageBufferSize(64 * 1024);     // 64KB（控制信号足够）
    return container;
}
```

**② `templates/monitor/list.html`** — 在每张服务器卡片的操作按钮区新增一个"📁 文件"按钮：

```html
<!-- 💻 Shell 按钮旁边 -->
<button class="btn btn-sm btn-outline-info me-1"
        onclick="window.open('/sftp/' + ${monitor.id})">
    📁 文件
</button>
```

其余现有文件**零修改**。

---

## URL 路由表

| 方法 | 路径 | 功能 |
|------|------|------|
| `GET` | `/sftp/{serverId:\d+}` | 文件浏览器页面（返回 `browser.html`），`\d+` 防止拦截静态资源 |
| `GET` | `/sftp/api/{serverId}/list?path=/home` | 列目录，返回 JSON 文件列表 |
| `GET` | `/sftp/api/{serverId}/download?path=/xxx/a.log` | 下载文件（文件流响应） |
| `WS` | `/sftp/upload/{serverId}` | **WebSocket 分块上传**（替代 REST multipart） |
| `POST` | `/sftp/api/{serverId}/delete` | 删除文件或目录 `body: {path, type}` |
| `POST` | `/sftp/api/{serverId}/rename` | 重命名 `body: {path, newName}` |
| `POST` | `/sftp/api/{serverId}/mkdir` | 新建文件夹 `body: {path}` |

> 上传不再使用 HTTP multipart，改为 WebSocket 二进制帧分块传输，避免大文件缓冲到 Java 临时目录。

---

## 接入流程

```
监控列表 → 点击服务器卡片上的 "📁 文件" 按钮
    ↓  window.open('/sftp/' + serverId)
新窗口打开文件浏览器页面
    ↓  页面加载完成
JS 调用 GET /sftp/api/{serverId}/list?path=/
    ↓  SftpService → SftpSessionManager 建立 SFTP 连接
返回 JSON 文件列表渲染表格
    ↓
用户操作：
  ├── 点击目录名 → 进入子目录（重新调用 list API）
  ├── 点击 ".." → 返回上级目录
  ├── 点击面包屑路径段 → 跳转到该路径
  ├── 选择文件上传 → 打开 WebSocket → 发送控制信号 → 分块发二进制帧 → 关闭 WS（零磁盘缓冲）
  ├── 点击"新建文件夹" → 弹窗输入名称 → POST mkdir
  ├── 点击"重命名" → 弹窗输入新名称 → POST rename
  ├── 点击"删除" → 确认弹窗 → POST delete
  └── 点击"下载" → GET download（浏览器直接下载）
```

---

## 页面 UI 布局

```
┌────────────────────────────────────────────────────────┐
│  📁 文件管理器  8.138.104.239:22 (root)   [💻 Shell]  │  ← 头部
├────────────────────────────────────────────────────────┤
│  📂  /home/user/projects                              │  ← 面包屑导航
│                                                        │
│  ┌────── 快捷目录 ──────┬────── 文件列表 ────────────┐  │
│  │                      │  □ 名称     大小  修改时间 权限│  │
│  │  📁  /               │  📁  ..      -     -      - │  │
│  │  📁  /home           │  📁  src     -  05-15 10:23 rwx│  │
│  │  📁  /etc            │  📁  target  -  05-14 08:15 rwx│  │
│  │  📁  /var/log        │  📄 pom.xml 4KB 04-20 14:30 rw-│  │
│  │  📁  /tmp            │  📄 app.log 8KB 05-15 11:00 rw-│  │
│  │                      │  📄 .env    256B 05-10 09:00 r--│  │
│  │  左侧面板（快捷导航） │                               │  │
│  │                      │       右侧面板（主文件列表）    │  │
│  └──────────────────────┴───────────────────────────────┘  │
│                                                        │
│  [📤 上传] [📁 新建文件夹] [✏️ 重命名] [🗑️ 删除] [🔄 刷新] │  ← 操作栏
└────────────────────────────────────────────────────────┘
```

---

## 补充设计细节

### 1. 统一 API 响应格式

所有 JSON 接口统一返回以下结构，前端根据 `success` 字段判断：

```json
{
  "success": true,
  "data": [ { "name": "pom.xml", "size": 4096, ... } ],
  "message": null
}

{
  "success": false,
  "data": null,
  "message": "SSH连接失败: Connection timed out"
}
```

Controller 中封装一个工具方法：

```java
private Map<String, Object> ok(Object data) {
    Map<String, Object> r = new HashMap<>();
    r.put("success", true); r.put("data", data); r.put("message", null);
    return r;
}

private Map<String, Object> fail(String msg) {
    Map<String, Object> r = new HashMap<>();
    r.put("success", false); r.put("data", null); r.put("message", msg);
    return r;
}
```

### 2. 页面注入时清除密码

`FileBrowserController.browser()` 查询 `SystemInfo` 后，注入模板前必须清空密码字段，防止 Thymeleaf 意外泄露：

```java
SystemInfo server = systemInfoMapper.selectById(String.valueOf(serverId));
server.setPassword(null);  // 注入模板前清空
model.addAttribute("server", server);
```

### 3. 路径穿越攻击防护

所有接收 `path` 参数的 API 接口需要校验路径合法性。`sanitizePath()` 提取到共享工具类 `SftpUtils`，避免 `SftpService` 和 `SftpUploadWebSocketHandler` 各维护一份重复实现：

```java
// com.show.sftp.SftpUtils 共享工具类
public class SftpUtils {
    public static String sanitizePath(String path) {
        if (path == null || path.trim().isEmpty()) return "/";
        // 禁止路径穿越
        if (path.contains("..")) {
            throw new IllegalArgumentException("路径不合法: 不允许包含 '..'");
        }
        // 统一以 / 开头
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
}
```

`SftpService` 和 `SftpUploadWebSocketHandler` 统一调用 `SftpUtils.sanitizePath()`，不各自定义私有方法。

`SftpService` 中 `listFiles` / `downloadFile` / `deleteFile` / `renameFile` / `createDirectory` 入口处先调用 `sanitizePath()`。

`SftpUploadWebSocketHandler` 中 `upload_start` 消息处理时同样对 `remotePath` 做 `sanitizePath()` 校验。

### 4. 文件大小格式化归属

| 规则 | 说明 |
|------|------|
| **后端存储** | `FileItem.size` 为 `long` 原始字节数 |
| **前端展示** | `browser.js` 中 `formatSize(bytes)` 函数格式化为 `4.2 KB` / `1.5 MB` / `2.3 GB` |
| **不做双份** | 后端不提供格式化后的字符串字段，避免前后端不一致 |

```javascript
function formatSize(bytes) {
    if (bytes === 0) return '-';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
}
```

### 5. WebSocket 分块上传协议（替代 REST multipart）

**设计目标：** 浏览器 → WebSocket → Java `channel.put()` OutputStream → 远程服务器。全程不落盘，不缓冲完整文件到内存。

#### 5.1 上传流程

```
浏览器                                     Java(SftpUploadWebSocketHandler)        远程服务器
  │                                              │                                   │
  │  ──[Text] upload_start {fileName,            │                                   │
  │    fileSize, remotePath}──────────────────→   │                                   │
  │                                               │  ── SSH connect ────────────────→ │
  │                                               │  ── channel.put(remotePath) 得到  │
  │                                               │     OutputStream ──────────────→ │
  │  ←──[Text] upload_ready ────────────────────  │                                   │
  │                                               │                                   │
  │  ──[Binary] chunk 0 (512KB) ──────────────→   │  ── write(bytes) ──────────────→  │
  │  ←──[Text] upload_progress {received,percent}  │                                   │
  │                                               │                                   │
  │  ──[Binary] chunk 1 (512KB) ──────────────→   │  ── write(bytes) ──────────────→  │
  │  ←──[Text] upload_progress {received,percent}  │                                   │
  │                                               │                                   │
  │  ... (重复直到文件发送完毕)                     │                                   │
  │                                               │                                   │
  │  ──[Binary] last chunk (≤512KB) ──────────→   │  ── write(bytes) ──────────────→  │
  │                                               │  ── outputStream.close() ───────→ │
  │                                               │  ── SSH disconnect ─────────────→ │
  │  ←──[Text] upload_complete {fileSize,         │                                   │
  │        timeMs, speed} ─────────────────────   │                                   │
```

#### 5.2 WebSocket 消息格式

| 方向 | 消息类型 | 帧类型 | 内容 |
|------|---------|--------|------|
| 浏览器→服务器 | `upload_start` | Text | `{"type":"upload_start","fileName":"file.iso","fileSize":1073741824,"remotePath":"/home/user/file.iso"}` |
| 服务器→浏览器 | `upload_ready` | Text | `{"type":"upload_ready"}` |
| 服务器→浏览器 | `upload_progress` | Text | `{"type":"upload_progress","received":524288,"percent":50.0}` |
| 浏览器→服务器 | (文件数据) | **Binary** | 裸二进制数据块，最大 524288 字节 |
| 服务器→浏览器 | `upload_complete` | Text | `{"type":"upload_complete","fileSize":1073741824,"timeMs":45230,"speed":"23.7MB/s"}` |
| 双向 | `upload_error` | Text | `{"type":"upload_error","message":"SSH连接失败: Connection refused"}` |
| 浏览器→服务器 | `upload_cancel` | Text | `{"type":"upload_cancel"}` |

**关键设计决策：**

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 首次消息 | Text JSON（控制信号） | 携带文件元数据 |
| 后续数据 | Binary 帧 | Base64 有 33% 开销，裸二进制最高效 |
| 分块大小 | 512KB | 平衡网络吞吐与内存占用 |
| 流控方式 | **等待 upload_progress 再发下一块**（滑动窗口） | 防止发太快撑爆 WebSocket 缓冲区或 SFTP 通道 |
| 完成判定 | 服务端累计 `receivedBytes >= fileSize` | 避免额外"结束信号"，天然可靠 |
| 断连清理 | `afterConnectionClosed` 中关流 + 断开 SSH | 用户关页面或网络中断时资源不泄漏 |

#### 5.3 流控机制

浏览器不能连续无限制发送二进制帧，每发完一块必须等待服务端回 `upload_progress` 才发下一块：

```javascript
ws.onmessage = function(event) {
    const msg = JSON.parse(event.data);
    if (msg.type === 'upload_progress') {
        updateProgress(msg.percent);
        sendNextChunk();  // 收到确认后再发下一块
    }
};
```

每次只发一块，确保 SFTP OutputStream `write()` 的背压能自然传导到浏览器。

#### 5.4 SftpUploadWebSocketHandler 内部状态管理

```java
import com.jcraft.jsch.*;
import com.show.entity.SystemInfo;
import com.show.mapper.SystemInfoMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SftpUploadWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SftpUploadWebSocketHandler.class);

    // 活跃上传会话：wsSessionId → UploadSession
    private final ConcurrentHashMap<String, UploadSession> uploads = new ConcurrentHashMap<>();

    @Autowired
    private SystemInfoMapper systemInfoMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession wsSession) {
        // 仅验证 URI 格式，实际连接在 upload_start 时按需建立
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        JsonNode json = objectMapper.readTree(message.getPayload());
        String type = json.get("type").asText();

        switch (type) {
            case "upload_start":
                // 1. 从 session attributes 读取 serverId（HandshakeInterceptor 在握手时注入）
                String serverId = (String) ws.getAttributes().get("serverId");
                // 2. 校验并清理 remotePath（防路径穿越）
                String rawPath = json.get("remotePath").asText();
                String remotePath = sanitizePath(rawPath);
                // 3. 查 DB 获取 SSH 连接信息
                SystemInfo server = systemInfoMapper.selectById(serverId);
                // 4. 建立 SSH 连接，openChannel("sftp")
                JSch jsch = new JSch();
                Session sshSession = jsch.getSession(server.getUsername(), server.getHost(), server.getPort());
                sshSession.setPassword(server.getPassword());
                sshSession.setConfig("StrictHostKeyChecking", "no");
                sshSession.connect(15000);
                ChannelSftp channel = (ChannelSftp) sshSession.openChannel("sftp");
                channel.connect(10000);
                // 5. 调用 channel.put(remotePath) 获得 OutputStream
                OutputStream out = channel.put(remotePath);
                // 6. 创建 UploadSession 存入 uploads map
                UploadSession session = new UploadSession(ws.getId(), remotePath,
                    json.get("fileSize").asLong(), sshSession, channel, out);
                uploads.put(ws.getId(), session);
                // 7. 回复 upload_ready
                sendJson(ws, Collections.singletonMap("type", "upload_ready"));
                break;

            case "upload_cancel":
                // 1. 移除并清理（删除不完整文件）
                UploadSession cancelled = uploads.remove(ws.getId());
                if (cancelled != null) cancelled.closeAndCleanup(true);
                break;
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
        UploadSession session = uploads.get(ws.getId());
        if (session == null || session.isCompleted()) return;

        try {
            byte[] bytes = new byte[message.getPayload().remaining()];
            message.getPayload().get(bytes);
            // 写入 SFTP 输出流
            session.getOutputStream().write(bytes);
            session.getOutputStream().flush();
            // 累计进度
            session.setReceivedBytes(session.getReceivedBytes() + bytes.length);
            double percent = (double) session.getReceivedBytes() / session.getFileSize() * 100;
            // 回复进度
            Map<String, Object> progressMsg = new HashMap<>();
            progressMsg.put("type", "upload_progress");
            progressMsg.put("received", session.getReceivedBytes());
            progressMsg.put("percent", percent);
            sendJson(ws, progressMsg);
            // 检查是否完成
            if (session.getReceivedBytes() >= session.getFileSize()) {
                session.setCompleted(true);
                long cost = System.currentTimeMillis() - session.getStartTime();
                String speed = String.format("%.1fMB/s",
                    (double) session.getFileSize() / cost / 1024);
                Map<String, Object> completeMsg = new HashMap<>();
                completeMsg.put("type", "upload_complete");
                completeMsg.put("fileSize", session.getFileSize());
                completeMsg.put("timeMs", cost);
                completeMsg.put("speed", speed);
                sendJson(ws, completeMsg);
                session.closeAndCleanup(false); // 正常完成，保留文件
                uploads.remove(ws.getId());
            }
        } catch (Exception e) {
            Map<String, Object> errorMsg = new HashMap<>();
            errorMsg.put("type", "upload_error");
            errorMsg.put("message", e.getMessage());
            sendJson(ws, errorMsg);
            session.closeAndCleanup(true);
            uploads.remove(ws.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        UploadSession session = uploads.remove(ws.getId());
        if (session != null) {
            session.closeAndCleanup(true); // 断连时删除不完整文件
        }
    }

    private void sendJson(WebSocketSession ws, Map<String, Object> msg) {
        try {
            String json = SftpUtils.OBJECT_MAPPER.writeValueAsString(msg);
            ws.sendMessage(new TextMessage(json));
        } catch (Exception ignored) {}
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable exception) {
        log.error("SFTP上传WebSocket异常", exception);
        UploadSession session = uploads.remove(ws.getId());
        if (session != null) {
            session.closeAndCleanup(true);
        }
    }
}
```

#### 5.5 UploadSession 状态对象

```java
public class UploadSession {
    private String wsSessionId;        // WebSocket 会话 ID
    private String serverId;           // 服务器 ID
    private Session sshSession;        // JSch SSH 会话
    private ChannelSftp channel;       // SFTP 通道
    private OutputStream outputStream; // channel.put() 返回的输出流
    private String remoteFilePath;     // 远程完整路径
    private long fileSize;             // 总大小（字节）
    private long receivedBytes;        // 已接收字节数
    private long startTime;            // 开始时间戳
    private boolean completed;         // 是否已完成
}
```

#### 5.6 上传进度推送

`browser.html` 中新增进度条容器：

```html
<!-- 上传进度条（默认隐藏） -->
<div id="uploadProgress" class="upload-progress" style="display:none;">
    <div class="upload-info">
        <span id="uploadFileName"></span>
        <span id="uploadPercent">0%</span>
    </div>
    <div class="progress-bar-wrapper">
        <div id="uploadProgressBar" class="progress-bar-fill" style="width:0%"></div>
    </div>
</div>
```

`browser.css` 中补充进度条样式。

#### 5.7 与其他上传方案的对比

| 维度 | REST multipart（原方案） | WebSocket 分块（现方案） |
|------|------------------------|------------------------|
| 大文件临时磁盘 | 有（Spring 写 temp 目录） | **无** |
| Java 内存 | 全部收完再转发 | **流式边收边推** |
| 1GB 文件对 Java 压力 | 高（2GB IO + 临时磁盘） | **低（仅内存滑动窗口）** |
| 进度反馈 | 无（需额外轮询） | **内置实时进度** |
| 实现复杂度 | 低 | 中 |
| 浏览器兼容性 | 全兼容 | WebSocket 主流浏览器均支持 |

#### 5.8 取消对 application.yml 的修改

> 不再需要配置 `spring.servlet.multipart.max-file-size`，因为不再使用 multipart 上传。删除之前的补充设计第 5 条配置内容。

### 6. 文件下载设计

#### 6.1 下载流程

```
浏览器                                     Java(FileBrowserController)                远程服务器
  │                                              │                                       │
  │  GET /sftp/api/{id}/download?path=/xxx/a.log │                                       │
  │  ──────────────────────────────────────────→  │                                       │
  │                                               │  sanitizePath(path) 防路径穿越       │
  │                                               │  Content-Disposition: attachment     │
  │                                               │  Content-Type: application/octet-stream
  │                                               │                                       │
  │                                               │  sftpService.downloadFile(id,path,   │
  │                                               │    response.getOutputStream())        │
  │                                               │    ├── sessionManager.connect()       │
  │                                               │    ├── channel.get(path, outputStream)│
  │                                               │    │       ── 文件数据流 ──────────→ │
  │                                               │    └── sessionManager.disconnect()    │
  │                                               │                                       │
  │  ←── 文件数据流 (HTTP Response) ─────────────  │                                       │
  │  ←── Content-Disposition 触发浏览器下载 ──────  │                                       │
```

#### 6.2 设计决策

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 传输方式 | **HttpServletResponse.getOutputStream() 流式写回** | 避免完整文件缓冲到 byte[]，OOM 风险为零 |
| SFTP API | `channel.get(remotePath, outputStream)` | JSch 内置流式传输，内部按块读取并写入输出流 |
| 连接管理 | 方法内 connect → try → finally disconnect | 确保无论成功或异常 SFTP 连接都被释放 |
| 响应头 | `Content-Disposition: attachment` | 强制浏览器弹下载框，而非直接打开文件 |
| 下载触发 | `window.location.href = '/sftp/api/...'` | 最简方式，无需 fetch + blob 中转 |
| 文件名提取 | `path.substring(path.lastIndexOf('/') + 1)` | 从远程完整路径提取文件名用于下载框默认名 |
| 大文件支持 | **无大小限制** | 流式传输不依赖文件大小，1GB 文件峰值内存 < 64KB |

#### 6.3 安全防护

| 防护措施 | 实现方式 |
|----------|----------|
| 路径穿越 | `SftpUtils.sanitizePath()` 拒绝包含 `..` 的路径 |
| 文件泄露 | 仅下载 SFTP 服务器上真实存在的文件，不提供绝对路径遍历 |
| 异常信息 | 服务端异常不返回堆栈，仅返回 `下载失败: ${message}` |

#### 6.4 错误处理策略

```
channel.get() 异常 → SftpService 包装为 RuntimeException
  → Controller 捕获 → response.sendError(500)
  → 浏览器显示下载失败提示
```

常见错误场景：

| 场景 | 表现 |
|------|------|
| 路径指向目录 | SFTP 服务端返回错误，客户端收到 500 |
| 文件不存在 | `channel.get()` 抛出 `SftpException`，返回 500 |
| 无读取权限 | SFTP 服务端拒绝，返回 500 |
| 网络中断 | `response.getOutputStream()` 写时抛 IOException，Controller 记录 error 日志 |
| SFTP 连接超时 | sessionManager.connect() 抛出 RuntimeException，Controller 捕获 |

#### 6.5 与备选方案的对比

| 维度 | byte[] 缓冲方案（备选） | 流式方案（当前采用） |
|------|------------------------|---------------------|
| 1GB 文件 Java 内存 | ≥1GB（byte[]） | **< 64KB（JSch 内部缓冲）** |
| SFTP 连接占用时间 | 短（读完即释放） | 略长（传输全程占用） |
| 实现复杂度 | 低 | 低 |
| 进度反馈 | 无（可加但轮询成本高） | **无**（下载进度交给 HTTP 和浏览器） |
| 文件截断风险 | 高（in.available() 不可靠） | **无（必须读到 EOF）** |

> **为什么下载不走 WebSocket？** 下载是读操作，HTTP Range + Content-Disposition 天然支持断点续传和浏览器保存对话框。引入 WebSocket 反而需要手动拼装二进制流到 Blob 再触发下载，增加复杂度且丢失浏览器原生下载能力。

---

### 7. 前端视图的四种状态覆盖

每个视图渲染时都需要处理四种状态：

| 状态 | 触发条件 | 前端表现 |
|------|----------|----------|
| 加载中 | `loadList()` 发起请求后，响应到达前 | 表格区域显示 `<div class="loading">🔄 加载中...</div>` |
| 空目录 | `data` 为空数组 | 显示 `<div class="empty">此目录为空</div>` |
| 错误 | `success === false` | 显示 `<div class="error">❌ ${message}</div>` |
| 正常 | `success === true` 且有数据 | 渲染文件列表表格 |

```javascript
async function loadList(path) {
    // 1. 显示加载中
    showLoading();
    try {
        const res = await fetch(`/sftp/api/${serverId}/list?path=${encodeURIComponent(path)}`);
        const json = await res.json();
        // 2. 根据响应状态分发
        if (json.success) {
            json.data && json.data.length > 0 ? renderFiles(json.data) : showEmpty();
        } else {
            showError(json.message);
        }
        updateBreadcrumb(path);
    } catch (e) {
        showError('网络请求失败: ' + e.message);
    }
}
```

### 8. 上传同名文件策略 + 进度展示

| 策略选择 | 说明 |
|----------|------|
| **直接覆盖**（采用） | SFTP `channel.put()` 默认行为，同名文件直接覆盖 |
| 自动重命名 | 不采用，避免用户找不到预期位置的文件 |

上传前前端检查当前文件列表，存在同名时弹窗确认：

```javascript
async function uploadFile() {
    // 防止并发上传：如果已有上传 WebSocket 连接未关闭，阻止新上传
    if (wsUpload && wsUpload.readyState === WebSocket.OPEN) {
        alert('已有上传任务进行中，请等待完成');
        return;
    }
    const input = document.createElement('input');
    input.type = 'file';
    input.onchange = function() {
        const file = input.files[0];
        // 检查当前列表是否有同名文件
        if (currentFiles.some(f => f.name === file.name)) {
            if (!confirm(`文件 "${file.name}" 已存在，是否覆盖？`)) return;
        }
        startWebSocketUpload(file); // 走 WebSocket 分块上传
    };
    input.click();
}
```

上传进度通过 `upload_progress` 消息驱动进度条更新，上传完成后刷新文件列表。

### 9. 行选中机制

**问题：** 重命名和删除等操作在全局工具栏中，必须指明操作目标文件。

**设计：**

| 交互 | 行为 |
|------|------|
| 单击行 | 高亮选中，记录为 `selectedFile` |
| 再次单击已选中的行 | 取消选中 |
| 选中后点击"重命名" | 对 `selectedFile` 执行重命名 |
| 选中后点击"删除" | 确认弹窗提示文件名，确认后删除 |
| 删除/重命名完成后 | 自动取消选中 |
| 切换目录/点击空白区域 | 自动取消选中 |
| 无选中时"重命名""删除"按钮 | disabled 状态（灰色不可点） |

**行渲染时绑定点击事件（renderFiles 内，`selectedFile` 为全局变量，声明见步骤 6 全局状态变量区）：**

```javascript
function renderFiles(items) {
    const tbody = document.getElementById('fileListBody');
    tbody.innerHTML = '';
    items.forEach(item => {
        const tr = document.createElement('tr');
        tr.className = 'file-row';
        // 显示文件/目录图标 + 名称
        const icon = item.isDir ? '📁' : '📄';
        tr.innerHTML = `
            <td class="file-name">${icon} ${item.name}</td>
            <td class="file-size">${formatSize(item.size)}</td>
            <td class="file-mtime">${item.lastModified}</td>
            <td class="file-perms">${item.permissions}</td>`;

        // 单击行选中/取消选中
        tr.addEventListener('click', function(e) {
            if (selectedFile && selectedFile.path === item.path) {
                // 已选中则取消
                selectedFile = null;
            } else {
                selectedFile = item;
            }
            updateSelectionUI();
        });
        tbody.appendChild(tr);
    });
    // 点击表格空白区域取消选中
    tbody.addEventListener('click', function(e) {
        if (e.target === tbody) {
            selectedFile = null;
            updateSelectionUI();
        }
    });
}

function updateSelectionUI() {
    // 清除所有行的 highlight 类
    document.querySelectorAll('#fileListBody tr').forEach(tr => tr.classList.remove('selected'));
    // 高亮当前选中行
    if (selectedFile) {
        const rows = document.querySelectorAll('#fileListBody tr');
        for (let tr of rows) {
            if (tr.textContent.includes(selectedFile.name)) {
                tr.classList.add('selected');
                break;
            }
        }
    }
    // 工具栏按钮状态
    document.getElementById('btnRename').disabled = !selectedFile;
    document.getElementById('btnDelete').disabled = !selectedFile;
}
```

**行选中样式（browser.css）：**

```css
#fileListBody tr.selected {
    background-color: rgba(13, 110, 253, 0.15);
    outline: 1px solid #0d6efd;
}
#fileListBody tr:hover {
    background-color: rgba(255, 255, 255, 0.05);
    cursor: pointer;
}
```

**操作栏按钮绑定：**

```html
<button onclick="renameFile()" id="btnRename" disabled>✏️ 重命名</button>
<button onclick="deleteFile()" id="btnDelete" disabled>🗑️ 删除</button>
```

```javascript
function renameFile() {
    if (!selectedFile) return;
    const newName = prompt('重命名: ' + selectedFile.name, selectedFile.name);
    if (newName && newName !== selectedFile.name) {
        fetch(`/sftp/api/${serverId}/rename`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({path: selectedFile.path, newName: newName})
        }).then(r => r.json()).then(json => {
            if (json.success) { selectedFile = null; refresh(); }
            else alert('重命名失败: ' + json.message);
        });
    }
}

function deleteFile() {
    if (!selectedFile) return;
    if (!confirm(`确定要删除 "${selectedFile.name}" 吗？`)) return;
    fetch(`/sftp/api/${serverId}/delete`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({path: selectedFile.path, type: selectedFile.isDir ? 'dir' : 'file'})
    }).then(r => r.json()).then(json => {
        if (json.success) { selectedFile = null; refresh(); }
        else alert('删除失败: ' + json.message);
    });
}
```

**目录切换时取消选中：**

```javascript
function navigateTo(path) {
    selectedFile = null;
    loadList(path);
}
```

### 10. 左侧快捷导航面板

**问题：** UI 布局图画了"快捷目录"面板，但没有任何代码实现。

**设计：** 在 `browser.js` 中硬编码一组常用路径作为快捷入口，渲染到左侧 `<div class="sidebar">` 中。后续可扩展为 localStorage 持久化用户自定义。

```javascript
// 快捷导航目录（硬编码默认值）
const QUICK_DIRS = [
    { label: '/',         path: '/' },
    { label: '/home',     path: '/home' },
    { label: '/etc',      path: '/etc' },
    { label: '/var/log',  path: '/var/log' },
    { label: '/tmp',      path: '/tmp' },
    { label: '/root',     path: '/root' },
    { label: '/opt',      path: '/opt' },
];

function renderSidebar(currentPath) {
    const sidebar = document.getElementById('sidebar');
    sidebar.innerHTML = '<div class="sidebar-title">📂 快捷目录</div>';
    QUICK_DIRS.forEach(dir => {
        const div = document.createElement('div');
        div.className = 'sidebar-item';
        if (dir.path === currentPath) {
            div.classList.add('active'); // 当前目录高亮
        }
        div.textContent = '📁 ' + dir.label;
        div.addEventListener('click', function() {
            navigateTo(dir.path);
        });
        sidebar.appendChild(div);
    });
}

// loadList 中调用 renderSidebar 保持同步
function loadList(path) {
    showLoading();
    selectedFile = null;
    renderSidebar(path); // 更新左侧面板高亮
    fetch(`/sftp/api/${serverId}/list?path=${encodeURIComponent(path)}`)
        .then(r => r.json())
        .then(json => {
            if (json.success) {
                json.data && json.data.length > 0 ? renderFiles(json.data) : showEmpty();
            } else {
                showError(json.message);
            }
            updateBreadcrumb(path);
        })
        .catch(e => showError('网络请求失败: ' + e.message));
}
```

**左侧面板 CSS（browser.css）：**

```css
.sidebar {
    width: 180px;
    min-width: 180px;
    background: #1a1d23;
    border-right: 1px solid #2d323b;
    padding: 8px 0;
    overflow-y: auto;
}
.sidebar-title {
    padding: 8px 16px;
    color: #6c757d;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.5px;
}
.sidebar-item {
    padding: 6px 16px;
    color: #c9d1d9;
    cursor: pointer;
    font-size: 14px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
.sidebar-item:hover {
    background: #2d323b;
    color: #ffffff;
}
.sidebar-item.active {
    background: rgba(13, 110, 253, 0.15);
    color: #0d6efd;
    border-right: 3px solid #0d6efd;
}
```

**browser.html 中新增左侧面板占位：**

```html
<div class="browser-layout">
    <div class="sidebar" id="sidebar"></div>
    <div class="main-content">
        <!-- 面包屑 + 操作栏 + 进度条 + 文件表格 -->
    </div>
</div>
```

**表格布局替换：** 原有的 `<table>` 外层套上 `browser-layout` flex 容器，sidebar 固定在左侧，文件区域占剩余宽度。

---

## 详细实现步骤

### 步骤 1 — `FileItem.java`

创建 `src/main/java/com/show/sftp/FileItem.java`

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | 文件名 |
| `path` | String | 完整路径 |
| `size` | long | 文件大小（字节） |
| `isDir` | boolean | 是否目录 |
| `permissions` | String | 权限字符串，如 `rwxr-xr-x` |
| `lastModified` | String | 最后修改时间格式化为 `yyyy-MM-dd HH:mm` |

纯 POJO，无任何依赖。全部字段构造器 + getter。

---

### 步骤 2 — `SftpSessionManager.java`

创建 `src/main/java/com/show/sftp/SftpSessionManager.java`

职责：根据 serverId 查询 DB 获取连接信息，建立 SFTP 连接，返回 ChannelSftp。

```
connect(Long serverId) → ChannelSftp:
  1. systemInfoMapper.selectById(id) → 获取 host/port/username/password
  2. new JSch() → session.setPassword()
  3. session.setConfig("StrictHostKeyChecking", "no")
  4. session.connect(15000)
  5. ChannelSftp channel = session.openChannel("sftp")
  6. channel.connect(10000)
  7. return channel

disconnect(ChannelSftp):
  1. channel.disconnect()
  2. channel.getSession().disconnect()
```

注入依赖: 唯一注入 `SystemInfoMapper`。

---

### 步骤 2b — `SftpUtils.java`

创建 `src/main/java/com/show/sftp/SftpUtils.java`

```java
package com.show.sftp;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SftpUtils {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 校验并清理路径，防止路径穿越攻击
     * 所有接收 path 参数的接口入口处调用
     */
    public static String sanitizePath(String path) {
        if (path == null || path.trim().isEmpty()) return "/";
        if (path.contains("..")) {
            throw new IllegalArgumentException("路径不合法: 不允许包含 '..'");
        }
        if (!path.startsWith("/")) path = "/" + path;
        return path;
    }
}
```

`SftpService` 和 `SftpUploadWebSocketHandler` 统一调用 `SftpUtils.sanitizePath()`，不各自定义私有方法。

---

### 步骤 3 — `SftpService.java`

创建 `src/main/java/com/show/sftp/SftpService.java`

5 个方法，每个内部调用 `SftpSessionManager` 建立/断开连接：

| 方法 | 内部实现 | 说明 |
|------|----------|------|
| `listFiles(serverId, path)` → `List<FileItem>` | `channel.ls(path)` → 遍历 `LsEntry`，过滤 `.` `..`，提取 `attrs.isDir()` / `attrs.getSize()` / `attrs.getMtimeString()` / `attrs.getPermissionsString()` 组装 FileItem | 根路径默认为 `/` |
| `downloadFile(serverId, path, outputStream)` | `channel.get(path, outputStream)` | response 输出流写入 |
| `deleteFile(serverId, path, isDir)` | 文件: `channel.rm(path)`，目录: `channel.rmdir(path)` | 调用前由前端确认 |
| `renameFile(serverId, oldPath, newName)` | `channel.rename(oldPath, newPath)` | newPath = 父目录路径 + "/" + newName |
| `createDirectory(serverId, path)` | `channel.mkdir(path)` | 单层创建 |

每个方法 try-catch-finally 确保 disconnect() 被调用。

---

### 步骤 4 — `FileBrowserController.java`

创建 `src/main/java/com/show/sftp/FileBrowserController.java`

| 方法 | 路径 | 实现 |
|------|------|------|
| `browser(serverId, model)` | `GET /sftp/{serverId:\d+}` | 查 SystemInfo 注入 model，返回 `sftp/browser` |
| `list(serverId, path)` | `GET /sftp/api/{serverId}/list` | `@ResponseBody` 返回 JSON |
| `download(serverId, path, response)` | `GET /sftp/api/{serverId}/download` | 设置 `Content-Disposition: attachment`，写 response 流 |
| `delete(body)` | `POST /sftp/api/{serverId}/delete` | `@RequestBody Map` 解析 path + isDir |
| `rename(body)` | `POST /sftp/api/{serverId}/rename` | `@RequestBody Map` 解析 path + newName |
| `mkdir(body)` | `POST /sftp/api/{serverId}/mkdir` | `@RequestBody Map` 解析 path |

> 上传功能不在 Controller 中实现，由 `SftpUploadWebSocketHandler` 处理。

@Controller + @RequestMapping("/sftp")，JSON 接口统一加 @ResponseBody。

**统一异常处理：** SftpService 抛出的异常（连接超时、权限拒绝、路径不存在等）必须捕获并返回 JSON 格式，不能传播到 Spring 返回 500 HTML 页面。

```java
@ExceptionHandler(Exception.class)
@ResponseBody
public Map<String, Object> handleException(Exception e) {
    log.error("SFTP操作异常", e);
    Map<String, Object> r = new HashMap<>();
    r.put("success", false);
    r.put("data", null);
    r.put("message", e.getMessage());
    return r;
}
```

---

### 步骤 4b — `UploadSession.java` + `SftpUploadWebSocketHandler.java`

创建 `src/main/java/com/show/sftp/UploadSession.java` 和 `src/main/java/com/show/sftp/SftpUploadWebSocketHandler.java`

**UploadSession** — 上传会话状态对象，包含资源清理方法：

```java
public class UploadSession {
    private String wsSessionId;
    private String remoteFilePath;
    private long fileSize;
    private long receivedBytes;
    private long startTime;
    private Session sshSession;
    private ChannelSftp channel;
    private OutputStream outputStream;
    private boolean completed;

    /**
     * 清理所有资源：关闭输出流 → 删除不完整文件 → 断开 SSH
     * @param deletePartialFile true=上传未完成时删除远程不完整文件
     */
    public void closeAndCleanup(boolean deletePartialFile) {
        // 1. 关闭 OutputStream（通知 SFTP 写入完成或中断）
        if (outputStream != null) {
            try { outputStream.close(); } catch (IOException ignored) {}
        }
        // 2. 如果上传未完成，删除远程不完整文件
        if (deletePartialFile && !completed && remoteFilePath != null && channel != null && channel.isConnected()) {
            try { channel.rm(remoteFilePath); } catch (SftpException ignored) {}
        }
        // 3. 断开 Channel + Session
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }
        if (sshSession != null && sshSession.isConnected()) {
            sshSession.disconnect();
        }
    }
}
```

> `closeAndCleanup(false)` 用于正常完成，保留远程文件。
> `closeAndCleanup(true)` 用于中途取消或断连，清理不完整文件。

**SftpUploadWebSocketHandler** — WebSocket 处理器（核心逻辑见 5.4 节代码骨架）：

| 回调 | 职责 |
|------|------|
| `handleTextMessage` | 处理 `upload_start`（建立 SSH/SFTP）和 `upload_cancel`（清理） |
| `handleBinaryMessage` | 将二进制帧写入 SFTP OutputStream，累计进度，回复 `upload_progress` |
| `afterConnectionClosed` | 移除会话，关闭流，断开 SSH（防止资源泄漏） |
| `handleTransportError` | 同 afterConnectionClosed，异常断连时清理 |

**修改 `WebSocketConfig.java`** 注册路由（`SftpUploadInterceptor` 作为 `WebSocketConfig` 的静态内部类已在第 ① 处定义，此处直接引用）：

```java
@Autowired private SftpUploadWebSocketHandler sftpUploadWebSocketHandler;

// 通过 HandshakeInterceptor 将 {serverId} 注入 session attributes，供 Handler 后续使用
registry.addHandler(sftpUploadWebSocketHandler, "/sftp/upload/{serverId}")
        .setAllowedOrigins("*")
        .addInterceptors(new WebSocketConfig.SftpUploadInterceptor());
```

---

### 步骤 5 — `browser.css`

创建 `src/main/resources/static/sftp/browser.css`

样式覆盖：
- 全局 flex 全屏布局
  - 头部栏：深色背景，服务器信息 + 关联按钮
  - `browser-layout`：flex 容器，左侧 sidebar 固定 180px，右侧 `main-content` 撑满剩余宽度
  - `sidebar`：深色背景，hover 高亮，active 项蓝色右边框
  - 面包屑：水平排列，路径段 clickable
  - 操作栏：按钮组水平排列
  - 文件表格：行 hover 高亮，行选中蓝色背景 + 边框
  - 响应式：小屏幕隐藏 sidebar，操作栏换行

---

### 步骤 6 — `browser.js`

创建 `src/main/resources/static/sftp/browser.js`

核心函数：

| 函数 | 作用 |
|------|------|
| `loadList(path)` | fetch list API → 渲染表格 + 更新面包屑 + 清空选中 |
| `renderFiles(items)` | 遍历 JSON 生成表格 HTML（📁/📄 图标、大小格式化、权限）+ 绑定行单击选中 |
| `updateBreadcrumb(path)` | 按 "/" 分段渲染可点击面包屑 |
| `navigateTo(path)` | selectedFile = null; loadList(path) |
| `goBack()` | 取当前路径父目录 → loadList |
| `startWebSocketUpload(file)` | 打开 WebSocket → 发 `upload_start` → 收到 `upload_ready` 后逐块发二进制 → 每块等 `upload_progress` 再发下一块 |
| `downloadFile(path)` | `window.location = /sftp/api/{id}/download?path=...` |
| `renameFile()` | 从 `selectedFile` 读取路径，prompt 输入新名 → fetch POST rename |
| `deleteFile(path)` | 从 `selectedFile` 读取路径 → confirm → fetch POST delete → refresh |
| `createDirectory()` | prompt 输入目录名 → fetch POST mkdir → refresh |
| `refresh()` | loadList(当前路径) |
| `renderSidebar(currentPath)` | 渲染左侧快捷导航面板，当前路径对应项高亮 |
| `updateSelectionUI()` | 高亮选中行 + 切换工具栏按钮 disabled 状态 |

**全局状态变量：**

```javascript
let selectedFile = null; // 当前选中的文件/目录项
```

数据流：
```
loadList("/home/user")
  → fetch /sftp/api/123/list?path=/home/user
  → 返回 JSON [{name, path, size, isDir, permissions, lastModified}, ...]
  → renderFiles(items) 更新表格
  → updateBreadcrumb("/home/user") 更新面包屑
```

WebSocket 分块上传核心逻辑：

```javascript
const CHUNK_SIZE = 512 * 1024; // 512KB
let wsUpload = null;
let currentFile = null;
let fileOffset = 0;
let chunkTimeout = null;    // 每块超时定时器（30s 无响应视为超时）
function startWebSocketUpload(file) {
    currentFile = file;
    fileOffset = 0;
    const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    wsUpload = new WebSocket(protocol + location.host + '/sftp/upload/' + serverId);

    wsUpload.onopen = function() {
        // 发送文件元数据
        wsUpload.send(JSON.stringify({
            type: 'upload_start',
            fileName: file.name,
            fileSize: file.size,
            remotePath: currentPath + '/' + file.name
        }));
    };

    wsUpload.onmessage = function(event) {
        const msg = JSON.parse(event.data);
        switch (msg.type) {
            case 'upload_ready':
                showProgress(currentFile.name, 0);
                clearTimeout(chunkTimeout); // 取消可能残留的超时
                sendNextChunk(); // 开始发送第一块
                break;
            case 'upload_progress':
                updateProgress(msg.percent);
                clearTimeout(chunkTimeout); // 收到进度确认，取消上一块超时
                sendNextChunk(); // 服务器确认后发送下一块
                break;
            case 'upload_complete':
                hideProgress();
                clearTimeout(chunkTimeout); // 完成，清除超时
                alert('上传完成: ' + msg.fileSize + ' 字节, ' + msg.speed);
                refresh();
                wsUpload.close();
                break;
            case 'upload_error':
                hideProgress();
                clearTimeout(chunkTimeout);
                alert('上传失败: ' + msg.message);
                wsUpload.close();
                break;
        }
    };

    wsUpload.onclose = function() {
        clearTimeout(chunkTimeout);
        wsUpload = null;
    };
}

function sendNextChunk() {
    if (!currentFile || fileOffset >= currentFile.size) return; // 发完了

    // 设置超时：30 秒内未收到服务器响应则判定上传失败
    chunkTimeout = setTimeout(function() {
        alert('上传超时：服务器长时间未响应，请检查网络后重试');
        wsUpload.close();
    }, 30000);

    const blob = currentFile.slice(fileOffset, Math.min(fileOffset + CHUNK_SIZE, currentFile.size));
    fileOffset += blob.size;
    wsUpload.send(blob); // 发送二进制帧
}
```

---

### 步骤 7 — `browser.html`

创建 `src/main/resources/templates/sftp/browser.html`

Thymeleaf 模板结构：

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <title>文件管理器</title>
    <link th:href="@{/sftp/browser.css}" rel="stylesheet">
</head>
<body>
    <!-- 头部 -->
    <div class="header" id="header">
        <span>📁 文件管理器</span>
        <span class="server-tag" th:text="${server.host} + ':' + ${server.port} + ' (' + ${server.username} + ')'"></span>
    </div>

    <!-- 主体布局：左侧快捷导航 + 右侧内容区 -->
    <div class="browser-layout">
        <!-- 左侧快捷导航 -->
        <div class="sidebar" id="sidebar"></div>

        <!-- 右侧内容区 -->
        <div class="main-content">
            <!-- 面包屑 -->
            <div class="breadcrumb" id="breadcrumb"></div>

            <!-- 操作栏 -->
            <div class="toolbar">
                <button onclick="uploadFile()">📤 上传</button>
                <button onclick="createDirectory()">📁 新建文件夹</button>
                <button onclick="renameFile()" id="btnRename" disabled>✏️ 重命名</button>
                <button onclick="deleteFile()" id="btnDelete" disabled>🗑️ 删除</button>
                <button onclick="refresh()">🔄 刷新</button>
            </div>

            <!-- 上传进度条（默认隐藏） -->
            <div id="uploadProgress" class="upload-progress" style="display:none;">
                <div class="upload-info">
                    <span id="uploadFileName"></span>
                    <span id="uploadPercent">0%</span>
                    <span id="uploadSpeed"></span>
                </div>
                <div class="progress-bar-wrapper">
                    <div id="uploadProgressBar" class="progress-bar-fill" style="width:0%"></div>
                </div>
            </div>

            <!-- 文件列表 -->
            <div class="file-table-wrapper">
                <table id="fileList">
                    <thead>
                        <tr><th>名称</th><th>大小</th><th>修改时间</th><th>权限</th></tr>
                    </thead>
                    <tbody id="fileListBody"></tbody>
                </table>
            </div>
        </div>
    </div>

    <!-- 注入服务器ID -->
    <script th:inline="javascript">
        const serverId = /*[[${server.id}]]*/ '0';
        const serverHost = /*[[${server.host}]]*/ '';
    </script>
    <script th:src="@{/sftp/browser.js}"></script>
</body>
</html>
```

---

### 步骤 8 — 修改 `monitor/list.html`

在 `templates/monitor/list.html` 中定位到 Shell 按钮处（当前代码）：

```html
<button class="btn btn-sm btn-outline-success me-1"
        onclick='openShell(${monitor.id})'>
    💻 Shell
</button>
```

在其后面插入：

```html
<button class="btn btn-sm btn-outline-info me-1"
        onclick="window.open('/sftp/' + ${monitor.id})">
    📁 文件
</button>
```

---

## 实现顺序

| 步骤 | 文件 | 验证方式 |
|------|------|----------|
| 1 | `FileItem.java` | 编译通过 |
| 2 | `SftpSessionManager.java` | 启动后能建立 SFTP 连接 |
| 2b | `SftpUtils.java` | 编译通过 |
| 3 | `SftpService.java` | API 可列目录 |
| 4 | `FileBrowserController.java` | Postman 调通列表/下载/删除/重命名/新建接口 |
| 4b | `UploadSession.java` + `SftpUploadWebSocketHandler.java` | WebSocket 上传可传输文件 |
| 5 | `static/sftp/browser.css` | 页面样式正确 |
| 6 | `static/sftp/browser.js` | 浏览器可导航/操作文件（含 WebSocket 上传） |
| 7 | `templates/sftp/browser.html` | 页面完整可交互 |
| 8 | 修改 `monitor/list.html` + `WebSocketConfig.java` | 点击按钮打开文件管理器 |

---

### 11. 多文件上传设计（扩展单文件上传为多文件队列）

#### 11.1 设计目标

| 目标 | 说明 |
|------|------|
| 支持一次选择多个文件上传 | 浏览器 `<input type="file" multiple>` 开启多选 |
| 队列顺序上传 | 文件逐个上传（不并发），保持提交顺序 |
| 复用连接 | 整个队列复用同一个 WebSocket 连接，避免重复建连开销 |
| 每个文件独立进度 | 队列中每个文件有独立的文件名、进度条、状态指示 |
| 总体进度 | 显示总进度（所有文件已传字节 / 总字节） |
| 单个文件失败不影响队列 | 某文件上传失败 → 标记错误 → 自动处理下一个 |

#### 11.2 队列架构

```
用户选择 N 个文件
       │
       ▼
┌─────────────────────────┐
│   前端队列 (Array)       │  ← browser.js 中维护
│  [file1, file2, file3]  │
└─────────────────────────┘
       │
       ▼ (取出 file1)
┌─────────────────────────┐
│  WebSocket 连接 (1 个)  │  ← 整个队列共用
│  上传 file1             │
│  → 完成 → 上传 file2   │
│  → 完成 → 上传 fileN   │
└─────────────────────────┘
       │
       ▼ (所有文件完成)
┌─────────────────────────┐
│ 关闭 WebSocket          │
│ 刷新文件列表            │
└─────────────────────────┘
```

**关键设计决策：**

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 队列位置 | **前端维护** | 后端不需要感知队列存在，每次 `upload_start` 就是单文件上传 |
| 处理方式 | **串行**（一个传完再传下一个） | 避免并发 SFTP 写入冲突，简化错误处理 |
| 连接复用 | 整个队列**共用 1 个 WebSocket** | 减少握手开销，SSH/SFTP 连接只建一次 |
| 后端改动 | **极简** | 后端只需知道这是队列中的第几个文件，不做队列管理 |
| 队列取消 | **清空队列 + 发 cancel** | 中断当前上传并清空剩余 |

#### 11.3 扩展 WebSocket 协议

在 `upload_start` 消息中增加队列位置字段，新增 `upload_queue_complete` 消息：

| 方向 | 消息类型 | 帧类型 | 内容 |
|------|---------|--------|------|
| 浏览器→服务器 | `upload_start` | Text | `{"type":"upload_start","fileName":"file.iso","fileSize":1073741824,"remotePath":"/home/file.iso","queueIndex":1,"queueTotal":5}` |
| 服务器→浏览器 | `upload_queue_complete` | Text | `{"type":"upload_queue_complete","totalFiles":5,"totalBytes":4294967296,"totalTimeMs":182300,"speed":"23.5MB/s"}` |

- `queueIndex`: 当前文件在队列中的序号（从 1 开始）
- `queueTotal`: 队列总文件数
- `upload_queue_complete` 在最后一个文件完成后发送，替代单文件的 `upload_complete`，包含整个队列的汇总统计

其他消息（`upload_ready`、`upload_progress`、`upload_error`、`upload_complete`）保持不变，其中 `upload_complete` 表示单个文件完成，前端收到后自动发送下一个文件的 `upload_start`。

#### 11.4 前端改动（browser.js）

##### 11.4.1 多文件选择 + 队列启动

```javascript
function uploadFiles() {
    if (wsUpload && wsUpload.readyState === WebSocket.OPEN) {
        alert('已有上传任务进行中，请等待完成');
        return;
    }

    var input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;  // 开启多选
    input.onchange = function() {
        var files = Array.from(input.files);
        if (files.length === 0) return;

        // 检查同名文件
        var existingNames = new Set();
        document.querySelectorAll('#fileListBody tr.file-row td.file-name').forEach(function(td) {
            var name = td.textContent.replace(/[📁📄]\s*/, '').trim();
            existingNames.add(name);
        });
        var hasConflict = files.some(function(f) { return existingNames.has(f.name); });
        if (hasConflict) {
            if (!confirm('存在同名文件，是否覆盖？')) return;
        }

        startQueueUpload(files);
    };
    input.click();
}
```

##### 11.4.2 队列管理

```javascript
// 多文件上传队列状态（替代单文件时的 currentFile + fileOffset）
var uploadQueue = [];       // 待上传文件数组
var queueIndex = 0;         // 当前正在上传的文件在 uploadQueue 中的索引
var queueTotalBytes = 0;    // 队列总字节数（用于总体进度）
var queueUploadedBytes = 0; // 队列已上传字节数
var queueStartTime = 0;     // 队列开始时间戳

function startQueueUpload(files) {
    uploadQueue = files;
    queueIndex = 0;
    queueTotalBytes = 0;
    queueUploadedBytes = 0;
    files.forEach(function(f) { queueTotalBytes += f.size; });

    showMultiProgress(files);
    queueStartTime = Date.now();

    // 打开 WebSocket（整个队列复用）
    var protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    wsUpload = new WebSocket(protocol + location.host + '/sftp/upload/' + serverId);

    wsUpload.onopen = function() {
        sendNextFileInQueue();  // 开始上传第一个文件
    };

    wsUpload.onmessage = function(event) {
        var msg = JSON.parse(event.data);
        switch (msg.type) {
            case 'upload_ready':
                clearTimeout(chunkTimeout);
                updateSingleFileProgress(queueIndex, 0);
                sendNextChunk();
                break;
            case 'upload_progress':
                clearTimeout(chunkTimeout);
                updateSingleFileProgress(queueIndex, msg.percent);
                updateOverallProgress();
                sendNextChunk();
                break;
            case 'upload_complete':
                clearTimeout(chunkTimeout);
                markFileComplete(queueIndex);            // 标记当前文件完成
                queueUploadedBytes += uploadQueue[queueIndex].size;
                queueIndex++;
                if (queueIndex < uploadQueue.length) {
                    sendNextFileInQueue();  // 还有下一个，继续发
                }
                // 最后一个文件：等待 upload_queue_complete（含汇总统计）
                break;
            case 'upload_error':
                clearTimeout(chunkTimeout);
                markFileError(queueIndex, msg.message);  // 标记当前文件失败
                queueUploadedBytes += uploadQueue[queueIndex].size;
                queueIndex++;
                if (queueIndex < uploadQueue.length) {
                    sendNextFileInQueue();  // 即使失败也继续下一个
                }
                // 最后一个文件出错：同完成，等待 upload_queue_complete
                break;
            case 'upload_queue_complete':
                hideMultiProgress();
                alert('全部上传完成: ' + msg.totalFiles + ' 个文件, ' + msg.speed);
                refresh();
                wsUpload.close();
                break;
        }
    };

    wsUpload.onclose = function() {
        clearTimeout(chunkTimeout);
        wsUpload = null;
    };
}

function sendNextFileInQueue() {
    if (queueIndex >= uploadQueue.length) return;
    var file = uploadQueue[queueIndex];
    currentFile = file;
    fileOffset = 0;

    wsUpload.send(JSON.stringify({
        type: 'upload_start',
        fileName: file.name,
        fileSize: file.size,
        remotePath: currentPath + '/' + file.name,
        queueIndex: queueIndex + 1,
        queueTotal: uploadQueue.length,
        queueTotalBytes: queueTotalBytes  // 携带总字节数，用于后端统计汇总
    }));
}
```

##### 11.4.3 取消队列

```javascript
function cancelQueue() {
    if (!wsUpload) return;
    uploadQueue = [];  // 清空剩余
    wsUpload.send(JSON.stringify({ type: 'upload_cancel' }));
    // ws.onclose 中清理
}
```

#### 11.5 多文件进度 UI

##### 11.5.1 队列进度面板结构（browser.html 替换原有单文件进度条）

```html
<!-- 上传进度面板（默认隐藏） -->
<div id="uploadProgress" class="upload-progress" style="display:none;">
    <!-- 总体进度 -->
    <div class="upload-overall">
        <div class="upload-info">
            <span id="overallLabel">📤 队列上传中...</span>
            <span id="overallPercent">0%</span>
        </div>
        <div class="progress-bar-wrapper">
            <div id="overallProgressBar" class="progress-bar-fill" style="width:0%"></div>
        </div>
    </div>
    <!-- 分隔线 -->
    <div class="upload-divider"></div>
    <!-- 单文件进度列表 -->
    <div id="fileProgressList"></div>
    <!-- 取消按钮 -->
    <div class="upload-actions">
        <button onclick="cancelQueue()" class="btn-cancel">取消全部</button>
    </div>
</div>
```

##### 11.5.2 前端进度渲染（browser.js）

```javascript
function showMultiProgress(files) {
    document.getElementById('uploadProgress').style.display = 'block';
    document.getElementById('overallLabel').textContent = '📤 队列上传中...';
    document.getElementById('overallPercent').textContent = '0%';
    document.getElementById('overallProgressBar').style.width = '0%';

    var container = document.getElementById('fileProgressList');
    container.innerHTML = '';
    files.forEach(function(file, index) {
        var item = document.createElement('div');
        item.className = 'file-progress-item';
        item.id = 'fileProgress_' + index;
        item.innerHTML =
            '<div class="file-progress-info">' +
                '<span class="file-progress-name">' + escapeHtml(file.name) + '</span>' +
                '<span class="file-progress-size">' + formatSize(file.size) + '</span>' +
                '<span class="file-progress-status" id="fileStatus_' + index + '">等待中</span>' +
            '</div>' +
            '<div class="file-progress-bar-row">' +
                '<div class="progress-bar-wrapper file-progress-bar-bg">' +
                    '<div class="progress-bar-fill file-progress-bar-fill" id="fileBar_' + index + '" style="width:0%"></div>' +
                '</div>' +
            '</div>';
        container.appendChild(item);
    });
}

function updateSingleFileProgress(index, percent) {
    var bar = document.getElementById('fileBar_' + index);
    var status = document.getElementById('fileStatus_' + index);
    if (bar) bar.style.width = percent + '%';
    if (status) status.textContent = percent.toFixed(1) + '%';
}

function updateOverallProgress() {
    var totalSent = queueUploadedBytes;
    if (queueIndex < uploadQueue.length && currentFile) {
        totalSent += fileOffset;
    }
    var percent = queueTotalBytes > 0 ? (totalSent / queueTotalBytes * 100) : 0;
    document.getElementById('overallPercent').textContent = percent.toFixed(1) + '%';
    document.getElementById('overallProgressBar').style.width = percent + '%';
}

function markFileComplete(index) {
    var bar = document.getElementById('fileBar_' + index);
    var status = document.getElementById('fileStatus_' + index);
    if (bar) { bar.style.width = '100%'; bar.style.background = '#238636'; }
    if (status) { status.textContent = '✅ 完成'; status.style.color = '#3fb950'; }
}

function markFileError(index, message) {
    var bar = document.getElementById('fileBar_' + index);
    var status = document.getElementById('fileStatus_' + index);
    if (bar) { bar.style.width = '100%'; bar.style.background = '#da3633'; }
    if (status) { status.textContent = '❌ ' + message; status.style.color = '#f85149'; }
}

function hideMultiProgress() {
    document.getElementById('uploadProgress').style.display = 'none';
    document.getElementById('overallProgressBar').style.width = '0%';
    document.getElementById('overallPercent').textContent = '0%';
    document.getElementById('fileProgressList').innerHTML = '';
}

function finishQueueUpload() {
    // 安全兜底：如果 upload_queue_complete 超过 3 秒未到达，自行完成
    // 正常情况下由后端 upload_queue_complete 驱动
    if (wsUpload && wsUpload.readyState === WebSocket.OPEN) {
        var totalTime = Date.now() - queueStartTime;
        var speed = (queueTotalBytes / 1024 / 1024) / (totalTime / 1000);
        alert('全部上传完成: ' + uploadQueue.length + ' 个文件, ' + speed.toFixed(1) + 'MB/s');
        hideMultiProgress();
        refresh();
        wsUpload.close();
    }
}
```

##### 11.5.3 队列进度面板样式（browser.css）

```css
/* 多文件上传队列进度 */
.upload-overall {
    margin-bottom: 4px;
}

.upload-divider {
    height: 1px;
    background: #2d323b;
    margin: 8px 0;
}

.file-progress-item {
    padding: 4px 0;
}

.file-progress-info {
    display: flex;
    justify-content: space-between;
    font-size: 13px;
    margin-bottom: 2px;
}

.file-progress-name {
    color: #c9d1d9;
    max-width: 50%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.file-progress-size {
    color: #484f58;
    font-size: 12px;
    margin: 0 8px;
    flex-shrink: 0;
}

.file-progress-status {
    color: #8b949e;
    font-size: 12px;
}

.file-progress-bar-row {
    padding-left: 0;
}

.file-progress-bar-bg {
    height: 4px;
}

.file-progress-bar-fill {
    height: 100%;
    border-radius: 2px;
    transition: width 0.3s ease;
}

.upload-actions {
    margin-top: 8px;
    text-align: right;
}

.btn-cancel {
    padding: 4px 12px;
    background: transparent;
    color: #f85149;
    border: 1px solid #f85149;
    border-radius: 6px;
    cursor: pointer;
    font-size: 12px;
}

.btn-cancel:hover {
    background: rgba(248, 81, 73, 0.15);
}
```

#### 11.6 后端改动

##### 11.6.1 SftpUploadWebSocketHandler 改动

后端改动核心原则：**后端不维护队列，只处理单文件上传，通过 `queueIndex` / `queueTotal` 感知队列进度**。

**关键问题：** 当前 handler（5.4 节）每次 `upload_start` 都创建全新 SSH 连接，每个文件完成后调用 `closeAndCleanup` 断开。队列模式下需要区分"首次上传"和"队列续传"：

```java
@Override
protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
    JsonNode json = objectMapper.readTree(message.getPayload());
    String type = json.get("type").asText();

    switch (type) {
        case "upload_start":
            String serverId = (String) ws.getAttributes().get("serverId");
            String rawPath = json.get("remotePath").asText();
            String remotePath = sanitizePath(rawPath);
            long fileSize = json.get("fileSize").asLong();
            int queueIndex = json.has("queueIndex") ? json.get("queueIndex").asInt() : 1;
            int queueTotal = json.has("queueTotal") ? json.get("queueTotal").asInt() : 1;

            // ★ 队列模式连接复用：检查是否已有 UploadSession
            UploadSession existing = uploads.get(ws.getId());

            if (existing != null && existing.isCompleted() && queueIndex > 1) {
                // 场景：上一个文件成功完成，复用 SSH/SFTP 连接
                existing.resetForNextFile(remotePath, fileSize);
                existing.setQueueIndex(queueIndex);
                existing.setQueueTotal(queueTotal);
                // queueTotalBytes 只在第一个文件时设置
                if (existing.getQueueTotalBytes() == 0) {
                    existing.setQueueTotalBytes(json.has("queueTotalBytes")
                        ? json.get("queueTotalBytes").asLong() : fileSize);
                }
                sendJson(ws, Collections.singletonMap("type", "upload_ready"));
                break;
            }

            // 场景：首次上传 或 上个文件出错后重建
            if (existing != null) {
                // 清理残留（出错的 session）
                existing.closeAndCleanup(true);
                uploads.remove(ws.getId());
            }

            // ---- 以下复用 5.4 节的建连逻辑 ----
            SystemInfo server = systemInfoMapper.selectById(serverId);
            // ... JSch连接、channel.put() ...
            UploadSession session = new UploadSession(ws.getId(), remotePath,
                fileSize, sshSession, channel, out);
            session.setQueueIndex(queueIndex);
            session.setQueueTotal(queueTotal);
            if (json.has("queueTotalBytes")) {
                session.setQueueTotalBytes(json.get("queueTotalBytes").asLong());
            } else {
                session.setQueueTotalBytes(fileSize);
            }
            session.setQueueStartTime(System.currentTimeMillis()); // 队列起始时间戳
            uploads.put(ws.getId(), session);
            sendJson(ws, Collections.singletonMap("type", "upload_ready"));
            break;

        case "upload_cancel":
            UploadSession cancelled = uploads.remove(ws.getId());
            if (cancelled != null) cancelled.closeAndCleanup(true);
            break;
    }
}
```

**`handleBinaryMessage` 完成判定改造：** 在单文件上传完成后，区分"队列中有后续文件"和"队列最后一个文件"：

```java
@Override
protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) {
    UploadSession session = uploads.get(ws.getId());
    if (session == null || session.isCompleted()) return;

    try {
        // ... 同 5.4 节，写入流、累计进度、回复 upload_progress ...

        // 检查是否完成
        if (session.getReceivedBytes() >= session.getFileSize()) {
            session.setCompleted(true);
            long cost = System.currentTimeMillis() - session.getStartTime();
            String speed = String.format("%.1fMB/s",
                (double) session.getFileSize() / cost / 1024);

            // 构建单文件完成消息（三种场景都需要）
            Map<String, Object> completeMsg = new HashMap<>();
            completeMsg.put("type", "upload_complete");
            completeMsg.put("fileSize", session.getFileSize());
            completeMsg.put("timeMs", cost);
            completeMsg.put("speed", speed);

            // ★ 判断是否队列最后一个文件
            boolean isLastInQueue = (session.getQueueIndex() >= session.getQueueTotal());

            if (isLastInQueue && session.getQueueTotal() > 1) {
                // 队列最后一个文件 → 单文件完成 + 队列汇总
                sendJson(ws, completeMsg);
                long queueCost = System.currentTimeMillis() - session.getQueueStartTime();
                String queueSpeed = String.format("%.1fMB/s",
                    (double) session.getQueueTotalBytes() / queueCost / 1024);
                Map<String, Object> queueMsg = new HashMap<>();
                queueMsg.put("type", "upload_queue_complete");
                queueMsg.put("totalFiles", session.getQueueTotal());
                queueMsg.put("totalBytes", session.getQueueTotalBytes());
                queueMsg.put("totalTimeMs", queueCost);
                queueMsg.put("speed", queueSpeed);
                sendJson(ws, queueMsg);
                session.closeAndCleanup(false);
                uploads.remove(ws.getId());
            } else if (isLastInQueue || session.getQueueTotal() <= 1) {
                // 单文件模式 → 仅单文件完成，关闭连接
                sendJson(ws, completeMsg);
                session.closeAndCleanup(false);
                uploads.remove(ws.getId());
            } else {
                // 队列中间文件 → 单文件完成，仅关 OutputStream，保留 SSH
                sendJson(ws, completeMsg);
                try { session.getOutputStream().close(); } catch (Exception ignored) {}
            }
        }
    } catch (Exception e) {
        // 出错时关闭所有连接，下一个文件 upload_start 会重建
        Map<String, Object> errorMsg = new HashMap<>();
        errorMsg.put("type", "upload_error");
        errorMsg.put("message", e.getMessage());
        sendJson(ws, errorMsg);
        session.closeAndCleanup(true);
        uploads.remove(ws.getId());
    }
}
```

##### 11.6.2 UploadSession 改动

增加队列相关字段和 `resetForNextFile()` 方法：

```java
public class UploadSession {
    // ...已有字段...
    private int queueIndex = 1;        // 当前文件在队列中的位置
    private int queueTotal = 1;        // 队列总文件数
    private long queueTotalBytes;      // 队列所有文件总字节数（用于汇总速度）
    private long queueStartTime;       // 队列开始时间戳（第一个文件 upload_start 的时间）

    // 在不关闭 SSH 连接的前提下重置上传状态
    public void resetForNextFile(String newRemotePath, long newFileSize) {
        this.remoteFilePath = newRemotePath;
        this.fileSize = newFileSize;
        this.receivedBytes = 0;
        this.startTime = System.currentTimeMillis();
        this.completed = false;
        try {
            if (this.outputStream != null) {
                this.outputStream.close();  // 关闭上一个文件的输出流
            }
            if (this.channel == null || !this.channel.isConnected()) {
                throw new RuntimeException("SFTP 通道已断开，无法继续队列上传");
            }
            this.outputStream = this.channel.put(newRemotePath);
        } catch (Exception e) {
            throw new RuntimeException("切换队列文件失败: " + e.getMessage(), e);
        }
    }
}
```

##### 11.6.3 后端改动要点汇总

| 改动点 | 改动内容 | 影响范围 |
|--------|----------|----------|
| `handleTextMessage` upload_start 分支 | 检查已有 UploadSession → 复用或新建；解析 `queueIndex`/`queueTotal`/`queueTotalBytes` | ~20 行 |
| `UploadSession` | 增加 `queueIndex`, `queueTotal`, `queueTotalBytes` 字段 + `resetForNextFile()` 方法 | ~30 行 |
| `handleBinaryMessage` 完成判定 | 单文件完成时判断 `queueIndex >= queueTotal`：是→关闭连接发 `upload_queue_complete`，否→仅关 OutputStream | ~15 行 |
| `handleBinaryMessage` 错误分支 | 出错时正常 `closeAndCleanup(true)` + `uploads.remove()`，下一个 `upload_start` 自动重建 | 0 行（行为不变） |
| SSH 连接复用 | 文件间关闭旧 OutputStream + `channel.put()` 新路径，SSH/SFTP 会话保持 | `resetForNextFile()` 中实现 |

**连接生命周期变化：**

```
单文件模式:   connect → upload_start → write chunks → complete → closeAndCleanup
队列模式:     connect → upload_start(file1) → complete → resetForNextFile
                     → upload_start(file2) → complete → resetForNextFile
                     → ...
                     → upload_start(fileN) → complete → closeAndCleanup → disconnect
```

#### 11.7 前端改动汇总

| 改动点 | 改动内容 |
|--------|----------|
| `uploadFile()` → `uploadFiles()` | `input.multiple = true`；从 `files[0]` 改为 `Array.from(files)` |
| 新增 `startQueueUpload(files)` | 维护队列数组、索引、总字节；打开 WS → `sendNextFileInQueue()` |
| 新增 `sendNextFileInQueue()` | 从队列取文件 → 发 `upload_start`（含 `queueIndex`, `queueTotal`） |
| 新增 `cancelQueue()` | 清空队列数组 + 发 `upload_cancel` |
| `ws.onmessage` 逻辑扩展 | 原单文件生命周期 → 增加队列调度：`complete` / `error` 后自动发下一文件 |
| 新增 `showMultiProgress(files)` | 渲染队列进度面板（每个文件一行 + 总体进度） |
| 新增 `updateSingleFileProgress(index, percent)` | 更新单文件进度条 |
| 新增 `updateOverallProgress()` | 累计已传字节 / 总字节 |
| 新增 `markFileComplete(index)` | 单文件标记完成（绿色） |
| 新增 `markFileError(index, msg)` | 单文件标记失败（红色）+ 继续下一个 |
| 新增 `hideMultiProgress()` | 隐藏并清空队列进度面板 |
| 删除 `showProgress`、`hideProgress`（单文件版本） | 被 `showMultiProgress` / `hideMultiProgress` 替代 |
| 删除单文件进度 DOM 元素 | browser.html 中移除旧 ID：`uploadFileName`、`uploadPercent`、`uploadProgressBar`，替换为队列版元素 |
| 按钮绑定 | `uploadFile()` → 替换为 `uploadFiles()` |

#### 11.8 错误处理策略

| 场景 | 行为 |
|------|------|
| 单个文件上传失败（SFTP 写入错误） | 标记该文件为错误状态（红色），自动开始下一个文件 |
| WebSocket 连接断开 | 中断整个队列，弹出提示，保留已上传文件 |
| 用户点击"取消全部" | 立即关闭 WebSocket → 后台 `closeAndCleanup(true)` 删除当前不完整文件；已完成的文件保留 |
| 浏览器关闭/刷新 | WebSocket `onclose` → 后台清理当前上传中的不完整文件 |
| 队列完成 | 清空进度面板，刷新文件列表，关闭 WebSocket |

#### 11.9 UI 布局（队列上传中）

```
┌────────────────────────────────────────────────────────┐
│  📤 队列上传中...                          45.2%      │  ← 总体进度
│  ████████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│────────────────────────────────────────────────────────│
│  📄 file1.iso   512 MB  ████████████████ 100%   ✅ 完成 │  ← 单文件进度
│  📄 file2.log   128 MB  ██████████░░░░░ 68.5%  68.5%  │
│  📄 file3.tar   1.2 GB  ░░░░░░░░░░░░░░░ 0%    等待中  │
│  📄 file4.txt   8 KB    ░░░░░░░░░░░░░░░ 0%    等待中  │
│────────────────────────────────────────────────────────│
│                                        [取消全部]      │
└────────────────────────────────────────────────────────┘
```

每个文件一行，左侧文件名，右侧状态（百分比 / "等待中" / "✅ 完成" / "❌ 失败"），中间细进度条。

#### 11.10 设计补充说明

##### 11.10.1 总体进度在出错时的准确性

当前总体进度计算方式为 `queueUploadedBytes / queueTotalBytes`，其中 `queueUploadedBytes` 在文件完成或出错时增加该文件的完整大小：

```javascript
// 完成时
queueUploadedBytes += uploadQueue[queueIndex].size;  // 准确
// 出错时
queueUploadedBytes += uploadQueue[queueIndex].size;  // 近似值（实际只传了部分）
```

这是一个已知的简化。出错文件的实际已传字节数不确定且难以追踪，使用完整大小作为近似值对剩余文件进度影响很小。UI 表现为出错瞬间总体进度跳升，跳升幅度 = 出错文件大小占比。如果要求完全精确，可追踪 `queueActuallySentBytes` 累加 `fileOffset`，当前不采纳。

##### 11.10.2 前端 `queueTotalBytes` 透传到后端

后端 `upload_queue_complete` 消息的 `totalBytes` 字段来自 `UploadSession.queueTotalBytes`，该值由第一个 `upload_start` 消息中的 `queueTotalBytes` 字段填充。

前端在 `startQueueUpload()` 中已计算 `queueTotalBytes`，在 `sendNextFileInQueue()` 发送第一个文件时携带：

```javascript
function sendNextFileInQueue() {
    var file = uploadQueue[queueIndex];
    currentFile = file;
    fileOffset = 0;
    wsUpload.send(JSON.stringify({
        type: 'upload_start',
        fileName: file.name,
        fileSize: file.size,
        remotePath: currentPath + '/' + file.name,
        queueIndex: queueIndex + 1,
        queueTotal: uploadQueue.length,
        queueTotalBytes: queueTotalBytes  // 仅首次需要，但每次携带更简单
    }));
}
```

后端在首个文件 `upload_start` 解析后存入 `UploadSession.queueTotalBytes`，后续文件沿用同一值。

##### 11.10.3 错误恢复的前后端协作

| 步骤 | 前端行为 | 后端行为 |
|------|----------|----------|
| 文件 N 上传中出错 | 收到 `upload_error` → `markFileError(N)` → `queueIndex++` → `sendNextFileInQueue()` | `session.closeAndCleanup(true)` → `uploads.remove()` |
| 文件 N+1 启动 | 发送 `upload_start`（正常流程） | `uploads.get(ws.getId())` 返回 null → 创建全新 SSH 连接 |
| 文件 N+1 完成 | 正常处理 `upload_complete` | 如为队列最后一个 → 正常关闭；否则等待下一个 |

这种设计确保：**出错后自动恢复，前端无感继续队列，后端为每个出错后的文件重建连接**，代价是每个出错后的文件多一次 SSH 握手。

##### 11.10.4 与原单文件模式的 DOM 兼容

队列进度面板替换原有单文件进度条时需注意：

| 旧元素 ID | 处理方式 |
|-----------|----------|
| `uploadFileName` | 删除，由 `overallLabel` + `fileProgressList` 中每行替代 |
| `uploadPercent` | 删除，由 `overallPercent` 替代 |
| `uploadProgressBar` | 删除，由 `overallProgressBar` + 每行 `fileBar_{n}` 替代 |
| `uploadProgress`（容器） | 复用，内部结构全部替换 |

即 `uploadProgress` 容器 ID 不变（JS 中 `showMultiProgress`/`hideMultiProgress` 仍用该 ID），内部 HTML 结构替换为队列版。

#### 11.11 与单文件模式的向后兼容

| 兼容维度 | 处理方式 |
|----------|----------|
| `upload_start` 消息 | `queueIndex` / `queueTotal` 为可选字段，不传则默认 `1/1`（单文件模式） |
| `UploadSession` 新字段 | 默认值 `queueIndex=1; queueTotal=1`，单文件上传使用默认值 |
| 旧版前端 | 不传队列字段，后端按单文件逻辑处理，行为完全不变 |
| 后端部署升级 | 先后端、后前端，单文件用户不受影响 |

---

## 与项目现有功能的对比区隔

| 维度 | 现有监控功能 | 文件浏览功能 |
|------|------------|------------|
| 包路径 | `com.show.controller.*`、`com.show.service.*` | `com.show.sftp.*` |
| 模板 | `templates/monitor/`、`templates/sshpage/` | `templates/sftp/browser.html` |
| 静态资源 | `static/shell/` | `static/sftp/browser.js` + `.css` |
| 传输协议 | SSH Exec（命令执行） | SFTP（文件操作） |
| 连接模式 | 每次采集新建 + 用完即断 | 每次 API 调用新建 + 用完即断 |
| 入口 | 监控卡片上的 Shell / 编辑 / 删除 按钮 | 监控卡片上的 📁 文件 按钮 |
| 上传方式 | 无 | **WebSocket 分块流式上传**（零磁盘缓冲） |