# SFTP 文件浏览管理器 — 设计文档

---

## 设计原则

整个 SFTP 功能前后端代码集中存放于 `com.show.sftp` 包，与项目现有 `com.show.controller`、`com.show.service` 等包完全解耦。唯一的外部依赖是通过 `SystemInfoMapper.selectById()` 获取服务器连接信息。

---

## 目录结构

### 后端：`com.show.sftp` 包

| 文件 | 职责 |
|------|------|
| `FileItem.java` | 文件/目录条目 DTO：name, path, size, isDir, permissions, lastModified |
| `SftpSessionManager.java` | SFTP 连接会话管理（连接/断开），从 DB 查询服务器凭证 |
| `SftpUtils.java` | 共享工具类：`sanitizePath()` 路径校验 + `OBJECT_MAPPER` JSON 单例 |
| `SftpService.java` | SFTP 业务操作：listFiles, downloadFile, deleteFile, renameFile, createDirectory |
| `FileBrowserController.java` | 页面路由 `GET /sftp/{id}` + JSON REST API `/sftp/api/{id}/xxx` |
| `SftpUploadWebSocketHandler.java` | WebSocket 分块上传处理器：控制信号 + 二进制块写入 SFTP 流 |
| `UploadSession.java` | 上传会话状态：进度跟踪、资源清理、队列模式复用 |

### 前端文件

| 文件 | 职责 |
|------|------|
| `templates/sftp/browser.html` | Thymeleaf 模板，渲染页面框架 + 注入服务器信息 |
| `static/sftp/browser.js` | 目录列取、导航、上传下载、删除重命名新建文件夹等全部交互。上传进度仅展示单文件进度条，无队列总进度条 |
| `static/sftp/browser.css` | 文件浏览器布局和样式 |

---

## 现有文件修改（2 处）

1. **`WebSocketConfig.java`** — 注册 `/sftp/upload/{serverId}` 路由 + `SftpUploadInterceptor`（提取 serverId） + `websocketContainer()` Bean（配置 1MB 二进制缓冲区）。详见 `WebSocketConfig.java` 全文。

2. **`templates/monitor/list.html`** — 服务器卡片的 Shell 按钮旁新增"📁 文件"按钮，`window.open('/sftp/' + ${monitor.id})`。

其余现有文件零修改。

---

## URL 路由表

| 方法 | 路径 | 功能 |
|------|------|------|
| `GET` | `/sftp/{serverId:\d+}` | 文件浏览器页面 |
| `GET` | `/sftp/api/{serverId}/list?path=/home` | 列目录 JSON |
| `GET` | `/sftp/api/{serverId}/download?path=/xxx` | 下载文件（流式响应） |
| `WS` | `/sftp/upload/{serverId}` | WebSocket 分块上传（多文件队列） |
| `POST` | `/sftp/api/{serverId}/delete` | 删除文件/目录 `{path, type}` |
| `POST` | `/sftp/api/{serverId}/rename` | 重命名 `{path, newName}` |
| `POST` | `/sftp/api/{serverId}/mkdir` | 新建文件夹 `{path}` |

---

## 设计决策汇总

### 1. 统一 API 响应格式

```json
{ "success": true, "data": [...], "message": null }
{ "success": false, "data": null, "message": "错误描述" }
```

Controller 中 `ok()` / `fail()` 工具方法 + `@ExceptionHandler` 统一异常转 JSON。详见 `FileBrowserController.java:147-161`。

### 2. 密码安全

Controller 注入模板前 `server.setPassword(null)`。详见 `FileBrowserController.java:45`。

### 3. 路径穿越防护

`SftpUtils.sanitizePath()` 共享工具方法：禁止 `..`、补全 `/` 前缀。`SftpService` 和 `SftpUploadWebSocketHandler` 统一调用。详见 `SftpUtils.java:15-22`。

### 4. 文件大小格式化

| 规则 | 说明 |
|------|------|
| 后端存储 | `FileItem.size` 为 `long` 原始字节数 |
| 前端展示 | `browser.js` 中 `formatSize(bytes)` 格式化为 `4.2 KB` / `1.5 MB` 等 |

### 5. WebSocket 分块上传协议

**流程：** `upload_start` → `upload_ready` → 二进制块 → `upload_progress` → ... → `upload_complete` → `upload_queue_complete`（每个队列末尾必定发送，含单文件模式）

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 首次消息 | Text JSON | 携带文件元数据 |
| 后续数据 | Binary 帧 | 避免 Base64 的 33% 开销 |
| 分块大小 | 512KB | 平衡网络吞吐与内存占用 |
| 流控 | 等待 `upload_progress` 再发下一块（滑动窗口） | 防止撑爆缓冲区 |
| 完成判定 | 服务端 `receivedBytes >= fileSize` | 天然可靠 |
| 前端进度面板 | 仅展示单文件进度条 | 去掉队列总进度条，避免单文件上传时出现两个进度条 |
| 面板关闭时机 | 收到 `upload_queue_complete` 后隐藏 | 统一由该消息触发，单文件/多文件均适用 |

详见 `SftpUploadWebSocketHandler.java` 全文和 `browser.js:254-353`。

### 6. 文件下载

`channel.get(path, response.getOutputStream())` 流式写回，不缓冲完整文件到内存。`Content-Disposition: attachment` 触发浏览器下载对话框。详见 `FileBrowserController.java:70-89` 和 `SftpService.java:79-89`。

### 7. 前端四种状态覆盖

每个视图渲染四种状态：加载中 / 空目录 / 错误 / 正常。详见 `browser.js:209-222` 和 `browser.js:42-67`。

### 8. 行选中机制

| 交互 | 行为 |
|------|------|
| 单击行 | 高亮选中 |
| 再次单击已选中行 | 取消选中 |
| 双击目录行 | 进入子目录 |
| 无选中时"重命名""删除""下载"按钮 | disabled |
| 切换目录 | 自动取消选中 |

详见 `browser.js:123-141` 和 `browser.js:70-121`。

### 9. 左侧快捷导航面板

`browser.js` 中 `QUICK_DIRS` 硬编码常用路径（`/`, `/home`, `/etc`, `/var/log`, `/tmp`, `/root`, `/opt`），渲染到 `#sidebar`。当前目录对应项高亮。详见 `browser.js:26-34` 和 `browser.js:173-189`。

### 10. 多文件队列上传

**核心设计：** 前端维护文件队列，WebSocket 连接复用，串行逐个上传。

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 队列位置 | 前端维护 | 后端不感知队列 |
| 处理方式 | 串行 | 避免 SFTP 并发写入冲突 |
| 连接复用 | 整个队列共用 1 个 WebSocket | 减少握手开销 |
| 单个失败 | 标记错误，继续下一个 | 不中断整个队列 |
| SSH 复用 | 文件间 `resetForNextFile()` 只关 OutputStream | 避免重复建连 |

**扩展协议字段：** `upload_start` 中新增 `queueIndex`, `queueTotal`, `queueTotalBytes`（可选，不传默认 1/1 即单文件模式）。无论单文件还是队列，最后一个文件完成后均发送 `upload_queue_complete`（含汇总统计），前端以此消息触发进度面板隐藏和 WebSocket 关闭。

详见：
- 后端：`UploadSession.java`（队列字段 + `resetForNextFile()` + 过渡期保护）、`SftpUploadWebSocketHandler.java`（`handleTextMessage` 连接复用逻辑 + `handleBinaryMessage` 完成判定分支）
- 前端：`browser.js:225-353`（`uploadFiles()` → `startQueueUpload()` → `sendNextFileInQueue()` → `onUploadMessage()` 队列调度）

**单文件向后兼容：** `queueIndex`/`queueTotal` 默认 `1/1`，旧版前端不传这些字段行为完全不变。

---

## 已知问题：队列上传竞态窗口

### 问题描述

队列模式中，文件 N 完成 → `resetForNextFile()` 设置 `completed=false` 并打开新 OutputStream → 此时若收到文件 N 的残留二进制帧，会写入文件 N+1 的输出流，导致文件损坏。

### 缓解措施

1. **客户端滑动窗口协议**（主防线）：每发一块等 `upload_progress` ack 才发下一块，`upload_complete` 发出后客户端不应有飞行中的块。正常流程下窗口不会被触发。
2. **服务端过渡期保护**（纵深防御）：`resetForNextFile()` 后设置 `transitioning=true`，20ms 内收到的二进制帧丢弃并记录日志。首个正常帧到达后清除标志。详见 `UploadSession.java:37-40` 和 `SftpUploadWebSocketHandler.java:136-144`。

### 影响评估

| 维度 | 评估 |
|------|------|
| 触发概率 | 极低（需流控协议被破坏或极端网络乱序） |
| 影响程度 | 中（后续文件内容被部分污染） |
| 可检测性 | 低（需 MD5/大小对比才能发现） |

**当前状态：已实施过渡期保护（方案 C），持续监控。**

---

## 与项目现有功能的区隔

| 维度 | 监控功能 | SFTP 文件浏览 |
|------|---------|-------------|
| 包路径 | `com.show.controller.*`、`com.show.service.*` | `com.show.sftp.*` |
| 模板/静态资源 | `templates/monitor/`、`static/shell/` | `templates/sftp/`、`static/sftp/` |
| 传输协议 | SSH Exec（命令执行） | SFTP（文件操作） |
| 连接模式 | 每次采集新建 + 用完即断 | 每次 API 调用新建 + 用完即断（上传队列内复用） |
| 入口 | 监控卡片上 Shell / 编辑 / 删除按钮 | 监控卡片上"📁 文件"按钮 |
| 上传方式 | 无 | WebSocket 分块流式上传（零磁盘缓冲） |

---

## 安全考虑

| 项目 | 说明 |
|------|------|
| SSH 主机密钥 | `StrictHostKeyChecking=no`（内网可信网络可接受） |
| WebSocket CORS | `setAllowedOrigins("*")`（内网工具权衡） |
| 密码存储 | SQLite 明文存储 SSH 凭据 |
| 路径穿越 | `SftpUtils.sanitizePath()` 拒绝 `..` |
| 下载文件名头注入 | `Content-Disposition` 文件名未过滤 CRLF（已知风险） |
| REST API 无 CSRF | 无 Spring Security，内网工具权衡 |

---

## 修复记录

### 2026-05-16：单文件上传进度条滞留 + 二次上传阻塞

**问题描述：** 单文件上传完成后进度条不消失，切换目录后依然存在；再次点击上传提示"已有上传任务进行中"。

**根因：** `SftpUploadWebSocketHandler.java` 中单文件模式的完成分支（`queueTotal <= 1`）只发送了 `upload_complete`，未发送 `upload_queue_complete`。前端依赖 `upload_queue_complete` 触发 `hideMultiProgress()` 和 `wsUpload.close()`，导致进度面板不隐藏、WebSocket 连接不关闭。

**修复：**
1. 合并服务端两个"最后一个文件"完成分支，统一发送 `upload_complete` + `upload_queue_complete`
2. 移除 HTML 中的队列总进度条区域（`overallLabel`/`overallPercent`/`overallProgressBar`），仅保留单文件进度列表
3. 删除 `browser.js` 中 `updateOverallProgress()` 函数及相关调用
4. 清理 `browser.css` 中 `.upload-overall` 和 `.upload-divider` 样式
