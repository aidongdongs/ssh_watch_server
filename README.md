# SpringBoot MyBatis SQLite — SSH 服务器监控管理系统

基于 Spring Boot + MyBatis + SQLite 的服务器远程监控管理平台，支持 SSH 连接采集性能指标、Web 终端、SFTP 文件管理等核心运维功能。

---

## 技术栈

| 层次 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 2.5.4 |
| ORM | MyBatis (注解式) | 2.2.0 |
| 数据库 | SQLite (JDBC) | 3.x |
| 连接池 | HikariCP | 内置 |
| 前端模板 | Thymeleaf | 内置 |
| UI | Bootstrap | 5.0.0-beta3 |
| 终端模拟 | xTerm.js | - |
| SSH/SFTP | JSch (mwiede 维护版) | 2.27.5 |
| 构建 | Maven | - |
| JDK | Java 8+ | 1.8 |

---

## 项目结构

```
src/main/java/com/show/
├── TestApplication.java              # Spring Boot 启动类
├── config/
│   ├── WebSocketConfig.java          # WebSocket 路由注册 + SftpUploadInterceptor
│   └── DatabaseInitializer.java      # 数据库自动迁移（当前注释关闭）
├── controller/
│   ├── Shouye.java                   # 首页路由 (/ → 监控列表)
│   ├── MonitorController.java        # 监控列表页面路由 (/monitor/list)
│   ├── MonitorDataController.java    # 监控数据 JSON 接口 (/monitor/list/data)
│   ├── SSHService.java               # 服务器 CRUD、数据采集触发、top/df/free 解析
│   ├── ShellController.java          # Web Shell 页面路由 (/shell/{id})
│   └── CustomErrorController.java    # 统一错误页面 (/error)
├── service/
│   ├── SshMonitorService.java        # SSH 监控服务接口
│   └── SshMonitorServiceImpl.java    # 多线程采集实现
├── mapper/
│   ├── SystemInfoMapper.java         # 服务器信息 CRUD (注解 SQL)
│   └── DiskUsageMapper.java          # 磁盘分区数据操作
├── entity/
│   ├── SystemInfo.java               # 服务器信息实体
│   ├── DiskUsage.java                # 磁盘分区实体
│   └── CollectResult.java            # 采集结果 DTO
├── task/
│   └── MonitorCollectorTask.java     # 单台服务器采集任务 (Callable)
├── util/
│   ├── SSHUtil.java                  # SSH 连接工具 (命令执行)
│   ├── MemoryUtil.java               # 内存解析工具
│   └── DiskUtil.java                 # 磁盘 df 输出解析
├── websocket/
│   ├── SSHWebSocketHandler.java      # WebSocket ↔ SSH 桥接 (Web 终端)
│   └── SSHConnectionInfo.java        # SSH 连接状态对象
└── sftp/                             # SFTP 文件浏览模块 (与主项目解耦)
    ├── FileBrowserController.java    # 页面路由 + REST API
    ├── SftpService.java              # SFTP 业务服务
    ├── SftpSessionManager.java       # SFTP 连接管理
    ├── SftpUtils.java                # 工具类 (防路径穿越)
    ├── SftpUploadWebSocketHandler.java # WebSocket 分块上传
    ├── UploadSession.java            # 上传会话状态
    └── FileItem.java                 # 文件条目 DTO

src/main/resources/
├── application.yml                   # Spring Boot 配置
├── templates/
│   ├── monitor/list.html             # 监控列表页 (服务器卡片)
│   ├── sshpage/add-server.html       # 添加服务器表单
│   ├── sshpage/edit.html             # 编辑服务器表单
│   ├── shell/console.html            # Web 终端页面
│   ├── sftp/browser.html             # SFTP 文件浏览器页面
│   └── error.html                    # 通用错误页面
├── static/
│   ├── bootstrap-5.0.0-beta3-dist/   # Bootstrap 5 CSS/JS
│   ├── js/
│   │   ├── xterm.min.css             # xTerm.js 终端样式
│   │   └── xterm.min.js              # xTerm.js 终端库
│   ├── shell/
│   │   ├── console.js                # Web 终端交互逻辑
│   │   └── console.css               # 终端样式
│   └── sftp/
│       ├── browser.js                # 文件浏览器交互逻辑
│       └── browser.css               # 文件浏览器样式
└── sftp/
    └── sftp.md                       # SFTP 模块设计文档
```

---

## 功能模块

### 1. 服务器管理

| 功能 | URL | 说明 |
|------|-----|------|
| 监控列表首页 | `GET /` | 跳转到 `/monitor/list` |
| 监控列表 | `GET /monitor/list` | 所有服务器卡片视图（CPU/内存/磁盘/进程 Top3） |
| 添加服务器 | `GET /service/add` | 填写 Host/Port/Username/Password 表单 |
| 编辑服务器 | `GET /ssh-service/select/edit/{id}` | 修改连接信息 |
| 删除服务器 | `GET /ssh-service/delete/{id}` | 删除服务器及关联数据 |
| 刷新采集 | `POST /ssh-service/refresh/all` | 重新采集所有服务器数据 |

### 2. 性能监控

监控列表以卡片形式展示每台服务器的实时数据（通过 SSH 执行系统命令采集）：

| 指标 | 来源命令 | 展示方式 |
|------|----------|----------|
| CPU 使用率 | `top -bn 1` | 进度条 + 百分比徽章 |
| CPU 进程 Top3 | `top -bn 1` 解析 | 排名表格 (红/黄/绿) |
| 内存使用率 | `free -h` + `free -b` | 进度条 + 已用/总量 |
| 内存进程 Top3 | `top -bn 1` 解析 | 排名表格 |
| 磁盘分区 | `df -hT` | 分区表格 + 使用率进度条 |

**排序功能：** 按 CPU / 内存 / 磁盘 / 时间排序，支持 1 列 / 2 列布局。

**IP 搜索：** 按 IP 或主机名模糊搜索。

### 3. Web SSH 终端

| 功能 | URL | 说明 |
|------|-----|------|
| 打开终端 | `GET /shell/{id}` | 在新窗口打开 Web Shell |
| SSH 连接 | `WS /ssh/**` | WebSocket ↔ SSH 双向桥接 |

从监控列表点击 **💻 Shell** 按钮，浏览器中打开 xTerm.js 终端窗口，通过 WebSocket 连接到远程服务器的 SSH Shell，支持完整交互式命令操作。

### 4. SFTP 文件浏览器

| 功能 | URL | 方法 |
|------|-----|------|
| 文件浏览器页 | `/sftp/{serverId}` | GET |
| 列目录 | `/sftp/api/{serverId}/list?path=` | GET |
| 下载文件 | `/sftp/api/{serverId}/download?path=` | GET |
| 删除文件/目录 | `/sftp/api/{serverId}/delete` | POST |
| 重命名 | `/sftp/api/{serverId}/rename` | POST |
| 新建文件夹 | `/sftp/api/{serverId}/mkdir` | POST |
| 分块上传 | `/sftp/upload/{serverId}` | WebSocket |

从监控列表点击 **📁 文件** 按钮打开文件浏览器，功能包括：

- 目录列取与导航（面包屑 + 左侧快捷目录）
- 文件选中机制（单击选中、双击进目录）
- 下载（流式传输，不缓冲到内存）
- WebSocket 分块上传（512KB 分块、滑动窗口流控、实时进度条）
- 删除 / 重命名 / 新建文件夹

**安全设计：**
- 路径穿越防护（所有 path 参数经 `sanitizePath()` 校验）
- 模板注入前清空密码
- 统一 JSON 响应格式 `{success, data, message}`
- 每次 API 调用独立建立/断开 SFTP 连接

> 详细设计文档见 `src/main/resources/sftp/sftp.md`

---

## 数据库

SQLite 文件数据库，存储在项目根目录 `identifier.sqlite`。

### 表结构

**system_monitor** — 服务器信息及采集数据

| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PK | 主键 |
| host | TEXT | 服务器 IP/域名 |
| port | INTEGER | SSH 端口 |
| username | TEXT | SSH 用户名 |
| password | TEXT | SSH 密码 |
| top_info | TEXT | `top -bn 1` 原始输出 |
| cpu_usage | REAL | CPU 使用率 % |
| free_info | TEXT | `free -h` 原始输出 |
| memory_usage | TEXT | 内存使用率详情 |
| disk_info | TEXT | `df -hT` 原始输出 |
| disk_usage_percent | INTEGER | 磁盘最高使用率 |
| top_processes | TEXT | CPU Top3 进程 (格式: `PID %CPU COMMAND`) |
| top_mem_processes | TEXT | 内存 Top3 进程 (格式: `PID %MEM COMMAND`) |
| created_at | TEXT | 采集时间 |

**disk_usage** — 磁盘分区详情

| 列 | 类型 | 说明 |
|----|------|------|
| id | INTEGER PK | 主键 |
| monitor_id | INTEGER FK | 关联 system_monitor.id |
| filesystem | TEXT | 文件系统 (如 /dev/vda1) |
| type | TEXT | 类型 (如 ext4) |
| size | TEXT | 总大小 (如 43G) |
| used | TEXT | 已用 (如 9.4G) |
| available | TEXT | 可用 (如 31G) |
| usage_percent | INTEGER | 使用率 % |
| mounted_on | TEXT | 挂载点 |
| created_at | TEXT | 采集时间 |

---

## 路由总表

| 方法 | URL | 功能 | 所属模块 |
|------|-----|------|----------|
| GET | `/` | 首页跳转 | 首页 |
| GET | `/monitor/list` | 监控列表页 | 监控 |
| GET | `/monitor/list/data` | 监控数据 JSON | 监控 |
| GET | `/monitor/search` | IP 模糊搜索 | 监控 |
| GET | `/service/add` | 添加服务器页 | 管理 |
| POST | `/ssh-service/add` | 添加服务器提交 | 管理 |
| POST | `/ssh-service/refresh/all` | 重新采集所有服务器 | 监控 |
| GET | `/ssh-service/select/edit/{id}` | 编辑服务器页 | 管理 |
| POST | `/ssh-service/edit` | 编辑服务器提交 | 管理 |
| GET | `/ssh-service/delete/{id}` | 删除服务器 | 管理 |
| GET | `/shell/{id}` | Web 终端页面 | SSH |
| GET | `/shell/console/{id}` | 终端备选路由 | SSH |
| WS | `/ssh/**` | WebSocket SSH 桥接 | SSH |
| GET | `/sftp/{serverId}` | SFTP 文件浏览器页 | SFTP |
| GET | `/sftp/api/{serverId}/list` | 列目录 | SFTP |
| GET | `/sftp/api/{serverId}/download` | 下载文件 | SFTP |
| POST | `/sftp/api/{serverId}/delete` | 删除文件 | SFTP |
| POST | `/sftp/api/{serverId}/rename` | 重命名 | SFTP |
| POST | `/sftp/api/{serverId}/mkdir` | 新建文件夹 | SFTP |
| WS | `/sftp/upload/{serverId}` | 分块上传 | SFTP |
| GET | `/error` | 统一错误页 | 全局 |

---

## 快速启动

### 环境要求

- JDK 8+
- Maven 3.x

### 启动步骤

```bash
# 1. 克隆项目
git clone <repo-url>
cd springboot_mybatis_sqlit

# 2. 编译打包
mvn clean package -DskipTests

# 3. 运行
mvn spring-boot:run

# 或直接运行 JAR
java -jar target/springboot_mybatis_sqlite-0.0.1-SNAPSHOT.jar
```

### 访问

- 监控首页：`http://localhost:8080/`
- 添加服务器：`http://localhost:8080/service/add`

### 配置

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:sqlite:identifier.sqlite   # SQLite 数据库文件路径
    # 可改为绝对路径：jdbc:sqlite:/data/identifier.sqlite
  thymeleaf:
    cache: false    # 开发阶段关闭模板缓存
```

---

## 开发说明

### 编码规范

- 包路径：`com.show.sftp` 为新功能包，与 `com.show.controller/service` 等现有包完全解耦
- SFTP 模块仅有 `SystemInfoMapper` 一个外部依赖
- 所有 path 参数统一使用 `SftpUtils.sanitizePath()` 防路径穿越
- 连接管理：每次操作独立建连 + try-finally 确保断开

### 数据库

SQLite 文件数据库无需额外安装。首次启动自动读取 `identifier.sqlite`，如需初始化空数据库，创建空文件即可。表结构由应用启动时自动检查补全（`DatabaseInitializer` 当前注释关闭，可手动开启）。
