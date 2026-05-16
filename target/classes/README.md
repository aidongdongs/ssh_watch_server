# 服务器监控 & Web Shell & SFTP 文件管理系统

基于 **Spring Boot 2.5.4** + **MyBatis** + **SQLite** 构建的轻量级服务器远程监控与 Web 终端管理平台。通过 SSH 协议远程采集 Linux 服务器的 CPU、内存、磁盘等性能指标，支持浏览器端 Web Shell 终端交互和 SFTP 文件浏览管理。

> 项目根目录下的 `README.md` 为完整版本文档，本文件为精简参考。

---

## 技术栈

| 层次 | 技术 |
|------|------|
| 框架 | Spring Boot 2.5.4 |
| ORM | MyBatis 2.2.0 + SQLite (xerial JDBC) |
| 模板 | Thymeleaf |
| 前端 | Bootstrap 5.0 beta3 + xterm.js 5.3.0 |
| SSH | JSch (com.github.mwiede 分支 2.27.5) |
| 连接池 | HikariCP (Spring Boot 默认) |
| 日志 | Log4j2 |
| 构建 | Maven (Java 8) |
| WebSocket | Spring WebSocket |

---

## 项目结构

```
src/main/java/com/show/
├── TestApplication.java              # 启动类
├── config/
│   └── WebSocketConfig.java           # WebSocket 路由注册（/ssh/** + /sftp/upload/**）
├── controller/
│   ├── CustomErrorController.java     # 自定义 404/500 错误页面
│   ├── MonitorController.java         # 监控列表页面路由
│   ├── MonitorDataController.java     # 监控数据 JSON API（排序/搜索）
│   ├── SSHService.java                # SSH 服务器 CRUD + 采集触发
│   ├── ShellController.java           # Web Shell 页面路由
│   └── Shouye.java                    # 首页路由
├── entity/
│   ├── CollectResult.java             # 并行采集结果 DTO（成功/失败）
│   ├── DiskUsage.java                 # 磁盘分区实体
│   └── SystemInfo.java                # 系统信息实体（含 @Component 注解，但作为 POJO 使用）
├── mapper/
│   ├── DiskUsageMapper.java           # 磁盘分区 Mapper（纯注解，无 XML）
│   └── SystemInfoMapper.java          # 系统信息 Mapper（@Many 嵌套加载 diskUsages）
├── service/
│   ├── SshMonitorService.java         # 采集服务接口
│   └── SshMonitorServiceImpl.java     # 并行采集 + 顺序写入实现
├── task/
│   └── MonitorCollectorTask.java      # 单台服务器采集任务（Callable）
├── util/
│   ├── DiskUtil.java                  # df -hT 输出解析（过滤虚拟文件系统）
│   ├── MemoryUtil.java                # free 输出解析（字节格式转换）
│   └── SSHUtil.java                   # JSch SSH 客户端封装
├── websocket/
│   ├── SSHConnectionInfo.java         # WebSocket ↔ SSH 连接映射
│   └── SSHWebSocketHandler.java       # WebSocket ↔ SSH 桥接处理器
└── sftp/                             # SFTP 文件浏览模块（与主项目解耦）
    ├── FileBrowserController.java    # 页面路由 + REST API
    ├── SftpService.java              # SFTP 业务服务
    ├── SftpSessionManager.java       # SFTP 连接管理
    ├── SftpUtils.java                # 工具类（防路径穿越 + JSON）
    ├── SftpUploadWebSocketHandler.java # WebSocket 分块上传
    ├── UploadSession.java            # 上传会话状态 + 队列支持
    └── FileItem.java                 # 文件条目 DTO

src/main/resources/
├── application.yml                    # 应用配置（数据源、Thymeleaf、MyBatis）
├── log4j2.xml                         # Log4j2 配置（控制台输出）
├── README.md                          # 项目文档（本文件）
├── sftp/
│   └── sftp.md                        # SFTP 模块设计文档
├── templates/
│   ├── error.html                     # 自定义 404/500 错误页面
│   ├── monitor/list.html              # 监控仪表盘（核心页面）
│   ├── shell/console.html             # Web Shell 终端页面
│   ├── sftp/browser.html              # SFTP 文件浏览器页面
│   ├── sshpage/add-server.html        # 添加 SSH 服务器表单
│   └── sshpage/edit.html              # 编辑 SSH 服务器表单
└── static/
    ├── bootstrap-5.0.0-beta3-dist/    # Bootstrap 框架（本地部署）
    ├── js/xterm.min.js / xterm.min.css # xterm.js 终端模拟器
    ├── shell/
    │   ├── console.js                 # 终端前端逻辑（WebSocket + xterm）
    │   └── console.css                # 终端主题样式（6种主题）
    └── sftp/
        ├── browser.js                 # 文件浏览器交互逻辑
        └── browser.css                # 文件浏览器样式

src/test/java/com/show/
└── SSHControllerTest.java             # SSH 采集集成测试（已注释）
```

---

## 功能模块

### 1. 服务器监控仪表盘

**页面入口:** `/monitor/list`（或 `/` 自动跳转）

**卡片布局（每个服务器一张卡片）：**

卡片内部从上到下分为三个区域：

**区域一：用户信息 + 性能指标（左侧盒子）**
```
┌─────────────────────────────────────┐
│  👤 用户信息 & 性能指标               │
│  ────────────────────────────────── │
│  👤 用户名: root                     │
│  [CPU: 0.0%] [内存: 3.63G/1.21G]    │
│  [磁盘: 71%] [分区: 1 个]           │
│  CPU 使用率详情             0.0%     │
│  ████████████ 进度条                 │
│  内存使用率详情            33.43%    │
│  ████████████ 进度条                 │
└─────────────────────────────────────┘
```

- **CPU 使用率**：数字徽章 + 进度条，颜色分级（<50% 绿, 50-80% 黄, >80% 红）
- **内存使用率**：格式 "已用/总计 使用率: xx.xx%"，带进度条
- **磁盘使用率**：显示所有分区最高使用率（徽章 + 进度条底层实现）
- **CPU 使用率进度条** 和 **内存使用率进度条** 直观展示

**区域二：文件系统（右侧盒子，与区域一左右并排）**
```
┌─────────────────────────────────────┐
│  💾 文件系统              N 个分区   │
│  ────────────────────────────────── │
│  文件系统  │ 挂载点  │ 类型 │ 使用率  │
│  ─────────┼────────┼─────┼──────── │
│  /dev/vda1│ /       │ ext4 │ [71%] █│
│  ...      │ ...     │ ...  │ ...     │
└─────────────────────────────────────┘
```

- 深色主题表格，行分隔线清晰
- 每行显示文件系统、挂载点、类型、使用率（徽章 + 进度条）

**区域三：进程 Top 3（左右并排）**
```
┌───────────────────┬───────────────────┐
│ 🔝 CPU使用率前三   │ 📊 内存使用率前三  │
│ 进程id│cpu使用率│名 │ 进程id│内存使用率│名│
│  1234 │  5.2%  │...│  5678 │  3.1%  │...│
│  ...  │  ...   │...│  ...  │  ...   │...│
└───────────────────┴───────────────────┘
```

- 前三名分别标红 #1 / 黄 #2 / 绿 #3
- 深色终端风格

**状态指示：**
- SSH 连接失败时卡片整体变红（`error-state` 样式）
- 显示最后更新时间戳

**排序功能：**
- 按 CPU / 内存 / 磁盘 / 时间排序（升序/降序切换）
- 1列 / 2列 布局切换（响应式，移动端自动单列）

**搜索功能：**
- 按 IP 或主机名模糊搜索

### 2. SSH 服务器 CRUD

**添加服务器** (`GET /service/add` → `POST /ssh-service/add`):
- 表单：主机地址、端口（默认22）、用户名、密码
- 前端验证（JS） + 后端验证（去重检查、非空校验）
- 去重检查：通过 `SystemInfoMapper.selectByHost()` 查询，相同 host 不允许重复

**编辑服务器** (`GET /ssh-service/select/edit/{id}` → `POST /ssh-service/edit`):
- 预填现有信息（Thymeleaf 回显）
- 密码可留空（不修改原有密码），通过 `updateSystemMonitorSelective` 动态 SQL 实现

**删除服务器** (`GET /ssh-service/delete/{id}`):
- 先删除关联的 `disk_usage` 记录（`DiskUsageMapper.deleteById(monitorId)`)
- 再删除 `system_monitor` 记录

### 3. 数据采集

**触发方式:** 点击"刷新"按钮 → `POST /ssh-service/refresh/all` → `SshMonitorServiceImpl.collectAllMonitors()`

**两阶段采集流程：**

```
阶段1（并行采集）:
  ExecutorService 固定线程池 (core = min(服务器数, 2*CPU核心数))
  ├── 每个服务器提交 MonitorCollectorTask (Callable)
  │   ├── SSH 连接 (30s 超时)
  │   ├── free -h / free -b → 内存解析 (15s 超时)
  │   ├── df -hT → 磁盘解析 + 虚拟文件系统过滤 (15s 超时)
  │   ├── top -bn 1 → CPU 解析 + Top 3 进程提取 (15s 超时)
  │   └── SSH 断开
  └── Future.get(60s) 等待所有任务完成，超时则取消

阶段2（顺序写入 — SQLite 单写入限制）:
  逐个处理 CollectResult:
  ├── 成功 → updateSystemMonitorSelective + 删除旧 disk_usage + 批量插入新 disk_usage
  └── 失败 → 写入错误标记（各字段置为 "SSH连接失败"）
```

**采集指标详情：**

| 指标 | 命令 | 解析策略 |
|------|------|----------|
| CPU 使用率 | `top -bn 1` | 正则提取 `%Cpu(s): X.X us` / `Cpu(s): X.X%us`，返回 `100 - idle` |
| 内存使用率 | `free -b` / `free -h` | 解析 Mem 行 total/used/free，计算 `used/total*100`；兼容 CentOS/Ubuntu 等不同 free 输出格式 |
| 磁盘使用率 | `df -hT` | 按列位置解析（兼容 6列/7列格式），过滤 tmpfs/devtmpfs/sysfs/proc/cgroup/overlay 等虚拟文件系统 |
| Top 进程 | `top -bn 1` 输出 | 定位 `%CPU` / `%MEM` 列和 COMMAND 列，排序取前三，格式化输出 `PID %CPU COMMAND` |

**兼容性：** CentOS、OpenEuler、Kylin、Ubuntu、Debian 等主流 Linux 发行版的 top / free / df 输出格式。

### 4. Web Shell 终端

**页面入口:** `/shell/{id}`（新窗口打开）

**技术实现：**
- 前端：xterm.js 终端模拟器
- 传输：WebSocket (`ws://host/ssh/{id}`) → SSH Channel Shell 直桥
- 协议：JSON 消息格式 `{type, content}`

**功能特性：**
- 完全交互式 Shell 体验
- 支持中文编码 (zh_CN.UTF-8)
- 终端尺寸自适应（ResizeObserver 监听尺寸变化 → WebSocket resize 消息 → `ChannelShell.setPtySize()`）
- **6 种颜色主题**：深色、浅色、绿色、德古拉、Nord、Gruvbox
- 主题持久化（localStorage）
- 窗口关闭自动清理 SSH 连接（`afterConnectionClosed` / `handleTransportError`）

**WebSocket 消息类型：**

| 类型 | 方向 | 说明 |
|------|------|------|
| `command` | 前端→后端 | 用户终端输入（写入 SSH PrintStream） |
| `resize` | 前端→后端 | PTY 窗口尺寸变化（cols, rows） |
| `terminal` | 后端→前端 | SSH 通道输出流转发 |
| `error` | 后端→前端 | 错误信息 |

**连接管理：**
- `SSHWebSocketHandler` 维护 `ConcurrentHashMap<String, SSHConnectionInfo>` 管理所有活跃连接
- 后台 daemon 线程持续读取 SSH stdout 并转发
- 清理方法确保 channel 和 session 被正确关闭

### 5. SFTP 文件浏览器

**页面入口:** 监控列表卡片点击 "📁 文件" 按钮 → `/sftp/{serverId}`

**功能特性：**
- 目录列取与导航（面包屑 + 左侧快捷目录面板）
- 文件选中机制（单击选中、双击进目录）
- 文件下载：`channel.get(path, outputStream)` 流式传输，不缓冲到内存
- WebSocket 分块上传：512KB 分块、滑动窗口流控、多文件队列串行上传、实时进度条
- 删除 / 重命名 / 新建文件夹
- 四种视图状态覆盖：加载中 / 空目录 / 错误 / 正常

**安全设计：**
- 路径穿越防护（`SftpUtils.sanitizePath()` 拒绝 `..`）
- 模板注入前清空密码
- 统一 JSON 响应 `{success, data, message}`
- 每次 API 调用独立建立/断开 SFTP 连接

> 详细设计见 `src/main/resources/sftp/sftp.md`

### 6. 错误处理

- 自定义 404/500 错误页面，中文提示
- 错误页面支持动态消息（`errorMessage` / `errorDetails` 属性）
- SSH 连接失败时卡片红色高亮（`error-state` CSS 类）
- 采集失败时写入默认错误文本到数据库
- REST API 返回空列表 + 日志记录（不抛出 500）

---

## 数据库表结构

### system_monitor（服务器系统信息）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 主键（自增） |
| host | TEXT | 主机地址（IP 或域名） |
| port | INTEGER | SSH 端口 |
| username | TEXT | SSH 用户名 |
| password | TEXT | SSH 密码（明文存储） |
| top_info | TEXT | top 命令原始输出 |
| cpu_usage | REAL | CPU 使用率百分比 |
| free_info | TEXT | free 命令原始输出 |
| memory_usage | TEXT | 格式化内存使用率字符串 |
| disk_info | TEXT | df 命令原始输出 |
| disk_usage_percent | INTEGER | 磁盘最高使用率 |
| top_processes | TEXT | CPU Top 3 进程（格式化文本） |
| top_mem_processes | TEXT | 内存 Top 3 进程（格式化文本） |
| created_at | TEXT | 采集时间（ISO 格式字符串） |

### disk_usage（磁盘分区详情）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 主键（自增） |
| monitor_id | INTEGER FK | 关联 system_monitor.id（级联删除） |
| filesystem | TEXT | 文件系统（如 /dev/vda1） |
| type | TEXT | 文件系统类型（如 ext4, xfs） |
| size | TEXT | 总大小 |
| used | TEXT | 已用空间 |
| available | TEXT | 可用空间 |
| usage_percent | INTEGER | 使用率百分比 |
| mounted_on | TEXT | 挂载点 |
| created_at | TEXT | 采集时间 |

---

## API 路由

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 首页（跳转监控列表） |
| GET | `/monitor/list` | 监控列表页面 |
| GET | `/monitor/list/data?sortBy=&order=` | 监控数据 JSON（支持排序） |
| GET | `/monitor/search?host=` | 模糊搜索主机 |
| GET | `/service/add` | 添加服务器页面 |
| POST | `/ssh-service/add` | 添加服务器（含去重检查） |
| GET | `/ssh-service/select/edit/{id}` | 编辑服务器页面 |
| POST | `/ssh-service/edit` | 编辑服务器 |
| GET | `/ssh-service/delete/{id}` | 删除服务器（级联删除磁盘记录） |
| POST | `/ssh-service/refresh/all` | 触发全量数据采集 |
| GET | `/shell/{id}` | Web Shell 页面 |
| WS | `/ssh/{id}` | WebSocket Shell 连接 |
| GET | `/sftp/{serverId}` | SFTP 文件浏览器页面 |
| GET | `/sftp/api/{serverId}/list?path=` | 列目录 |
| GET | `/sftp/api/{serverId}/download?path=` | 下载文件 |
| POST | `/sftp/api/{serverId}/delete` | 删除文件/目录 |
| POST | `/sftp/api/{serverId}/rename` | 重命名 |
| POST | `/sftp/api/{serverId}/mkdir` | 新建文件夹 |
| WS | `/sftp/upload/{serverId}` | WebSocket 分块上传 |
| GET | `/error` | 错误页面 |

---

## 构建与运行

```bash
# Maven 打包可执行 JAR
mvn clean package -DskipTests

# 运行
java -jar target/springboot_mybatis_sqlite-0.0.1-SNAPSHOT.jar

# 访问
http://localhost:8080/
```

默认使用项目根目录下的 `identifier.sqlite` 作为数据库文件。

---

## 安全注意事项

- SSH 密码以**明文**存储在 SQLite 数据库中
- REST API 返回监控数据时**清除密码字段**（`MonitorDataController` 中调用 `setPassword(null)`）
- SSH 连接时禁用了 `StrictHostKeyChecking`（`session.setConfig("StrictHostKeyChecking", "no")`）
- WebSocket 允许跨域连接（`setAllowedOrigins("*")`）
- 建议在生产环境中使用密钥认证替代密码认证，并启用 HTTPS/WSS
