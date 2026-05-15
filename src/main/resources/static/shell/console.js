// queueMicrotask polyfill
if (typeof window.queueMicrotask !== 'function') {
    window.queueMicrotask = function(callback) {
        Promise.resolve()
            .then(callback)
            .catch(function(err) {
                setTimeout(function() {
                    throw err;
                });
            });
    };
}

// replaceChildren polyfill
if (!Element.prototype.replaceChildren) {
    Element.prototype.replaceChildren = function() {
        while (this.lastChild) {
            this.removeChild(this.lastChild);
        }
        if (arguments.length > 0) {
            this.append.apply(this, arguments);
        }
    };
}

// ========== 主题系统 ==========
const themes = {
    dark: {
        name: '深色',
        xterm: {
            background: '#1e1e1e',
            foreground: '#cccccc',
            cursor: '#cccccc',
            cursorAccent: '#1e1e1e',
            selectionBackground: '#5a5a5a'
        }
    },
    light: {
        name: '浅色',
        xterm: {
            background: '#ffffff',
            foreground: '#212529',
            cursor: '#212529',
            cursorAccent: '#ffffff',
            selectionBackground: '#b0d4ff'
        }
    },
    green: {
        name: '绿色',
        xterm: {
            background: '#000000',
            foreground: '#00ff00',
            cursor: '#00ff00',
            cursorAccent: '#000000',
            selectionBackground: '#003300'
        }
    },
    dracula: {
        name: '德古拉',
        xterm: {
            background: '#282a36',
            foreground: '#f8f8f2',
            cursor: '#f8f8f2',
            cursorAccent: '#282a36',
            selectionBackground: '#44475a',
            black: '#21222c',
            red: '#ff5555',
            green: '#50fa7b',
            yellow: '#f1fa8c',
            blue: '#bd93f9',
            magenta: '#ff79c6',
            cyan: '#8be9fd',
            white: '#f8f8f2',
            brightBlack: '#6272a4',
            brightRed: '#ff6e6e',
            brightGreen: '#69ff94',
            brightYellow: '#ffffa5',
            brightBlue: '#d6acff',
            brightMagenta: '#ff92df',
            brightCyan: '#a4ffff',
            brightWhite: '#ffffff'
        }
    },
    nord: {
        name: 'Nord',
        xterm: {
            background: '#2e3440',
            foreground: '#d8dee9',
            cursor: '#d8dee9',
            cursorAccent: '#2e3440',
            selectionBackground: '#4c566a',
            black: '#3b4252',
            red: '#bf616a',
            green: '#a3be8c',
            yellow: '#ebcb8b',
            blue: '#81a1c1',
            magenta: '#b48ead',
            cyan: '#88c0d0',
            white: '#e5e9f0',
            brightBlack: '#4c566a',
            brightRed: '#bf616a',
            brightGreen: '#a3be8c',
            brightYellow: '#ebcb8b',
            brightBlue: '#81a1c1',
            brightMagenta: '#b48ead',
            brightCyan: '#8fbcbb',
            brightWhite: '#eceff4'
        }
    },
    gruvbox: {
        name: 'Gruvbox',
        xterm: {
            background: '#282828',
            foreground: '#ebdbb2',
            cursor: '#ebdbb2',
            cursorAccent: '#282828',
            selectionBackground: '#3c3836',
            black: '#282828',
            red: '#cc241d',
            green: '#98971a',
            yellow: '#d79921',
            blue: '#458588',
            magenta: '#b16286',
            cyan: '#689d6a',
            white: '#a89984',
            brightBlack: '#928374',
            brightRed: '#fb4934',
            brightGreen: '#b8bb26',
            brightYellow: '#fabd2f',
            brightBlue: '#83a598',
            brightMagenta: '#d3869b',
            brightCyan: '#8ec07c',
            brightWhite: '#ebdbb2'
        }
    }
};

let currentTheme = localStorage.getItem('console-theme') || 'dark';

function applyTheme(themeName) {
    if (!themes[themeName]) return;
    currentTheme = themeName;
    localStorage.setItem('console-theme', themeName);

    // 切换 body class（CSS 变量随之切换）
    document.body.className = 'theme-' + themeName;

    // 更新 xterm.js 主题（xterm 5.3.0 不支持动态 setOption，只能重建终端）
    if (terminal) {
        recreateTerminal(themes[themeName].xterm);
    }

    // 更新按钮高亮
    document.querySelectorAll('.theme-btn').forEach(function(btn) {
        btn.classList.toggle('active', btn.dataset.theme === themeName);
    });
}

// 终端输入处理器（提取为命名函数，以便主题切换时重用）
function handleTerminalInput(data) {
    if (connected && websocket.readyState === WebSocket.OPEN) {
        websocket.send(JSON.stringify({
            type: "command",
            content: data
        }));
    } else {
        terminal.write("\r\n\x1b[31m未连接到服务器\x1b[0m\r\n");
    }
}

// 重建终端（切换主题时使用）
function recreateTerminal(themeConfig) {
    // 保存当前终端内容
    var savedLines = [];
    if (terminal) {
        var buf = terminal.buffer.active;
        for (var i = 0; i < buf.length; i++) {
            var line = buf.getLine(i);
            if (line) {
                savedLines.push(line.translateToString());
            }
        }
        terminal.dispose();
    }

    var termContainer = document.getElementById('terminal');
    termContainer.innerHTML = '';

    terminal = new Terminal({
        cursorBlink: true,
        theme: {
            background: themeConfig.background,
            foreground: themeConfig.foreground,
            cursor: themeConfig.cursor,
            cursorAccent: themeConfig.cursorAccent,
            selectionBackground: themeConfig.selectionBackground
        },
        fontSize: 14,
        fontFamily: 'Consolas, Monaco, "Courier New", monospace',
        allowTransparency: false,
        disableStdin: false,
        convertEol: true,
        cols: 80,
        rows: 24
    });

    terminal.open(termContainer);

    // 重写已保存的内容
    if (savedLines.length > 0) {
        terminal.write(savedLines.join('\r\n'));
    }

    // 重新绑定输入事件
    terminal.onData(handleTerminalInput);

    // 重新适配尺寸并聚焦
    requestAnimationFrame(function() {
        fitTerminal();
        terminal.focus();
    });
}

console.log("服务器信息:", {serverId, serverHost, serverPort, serverUsername});

let websocket;
let connected = false;
let terminal;
let currentPath = "/";
let selectedLocalFile = null;
let selectedRemoteFile = null;

// 页面加载完成后初始化
window.onload = function() {
    console.log("页面加载完成，开始初始化终端");

    // 加载保存的主题
    applyTheme(currentTheme);

    // 绑定主题按钮事件
    document.querySelectorAll('.theme-btn').forEach(function(btn) {
        btn.addEventListener('click', function() {
            applyTheme(this.dataset.theme);
        });
    });

    initTerminal();
    initWebSocket();
    setupDragAndDrop();
};

// 初始化xterm.js终端
function initTerminal() {
    console.log("初始化xterm.js终端");
    var themeConfig = themes[currentTheme].xterm;
    terminal = new Terminal({
        cursorBlink: true,
        theme: {
            background: themeConfig.background,
            foreground: themeConfig.foreground,
            cursor: themeConfig.cursor,
            cursorAccent: themeConfig.cursorAccent,
            selectionBackground: themeConfig.selectionBackground
        },
        fontSize: 14,
        fontFamily: 'Consolas, Monaco, "Courier New", monospace',
        allowTransparency: false,
        disableStdin: false,
        convertEol: true,
        cols: 80,
        rows: 24
    });

    terminal.open(document.getElementById('terminal'));
    terminal.write("终端初始化完成\r\n");
    terminal.write("服务器信息: " + serverHost + ":" + serverPort + " (" + serverUsername + ")\r\n");

    // 使用 ResizeObserver 监听终端容器尺寸变化
    const terminalEl = document.getElementById('terminal');
    const resizeObserver = new ResizeObserver(function() {
        requestAnimationFrame(function() {
            fitTerminal();
        });
    });
    resizeObserver.observe(terminalEl);
    // 首次渲染后立即适配尺寸
    requestAnimationFrame(function() {
        fitTerminal();
    });
    // window resize 兜底（某些场景 ResizeObserver 不触发）
    window.addEventListener('resize', function() {
        requestAnimationFrame(function() {
            fitTerminal();
        });
    });

    // 监听终端输入
    terminal.onData(handleTerminalInput);

    // 初始时聚焦终端
    setTimeout(function() {
        terminal.focus();
    }, 100);
}

// 自适应终端尺寸：基于窗口尺寸减去 20px 边距和标题栏
function fitTerminal() {
    if (!terminal) return;

    var header = document.querySelector('.terminal-header');
    var headerHeight = header ? header.offsetHeight : 40;

    var width = window.innerWidth - 40;
    var height = window.innerHeight - 40 - headerHeight;

    if (width <= 0 || height <= 0) return;

    // 精确测量字符尺寸：通过 xterm 已渲染的 DOM 计算
    var charWidth = 9;
    var charHeight = 20;
    var screen = document.querySelector('#terminal .xterm-screen');
    if (screen) {
        var child = screen.firstElementChild;
        if (child) {
            var w = child.clientWidth;
            var cols = terminal.cols;
            if (w > 0 && cols > 0) charWidth = w / cols;
            var h = child.clientHeight;
            var rows = terminal.rows;
            if (h > 0 && rows > 0) charHeight = h / rows;
        }
    }

    var newCols = Math.max(20, Math.floor(width / charWidth));
    var newRows = Math.max(5, Math.floor(height / charHeight));

    // 始终调整 xterm.js 尺寸（即使 cols/rows 没变，也确保 PTY 收到正确的尺寸）
    terminal.resize(newCols, newRows);
    console.log("终端尺寸适配: " + newCols + "x" + newRows + " (窗口 " + width + "x" + height + ", 字符 " + charWidth.toFixed(1) + "x" + charHeight.toFixed(1) + ")");

    // 同步尺寸到后端 PTY
    if (connected && websocket && websocket.readyState === WebSocket.OPEN) {
        websocket.send(JSON.stringify({
            type: 'resize',
            cols: newCols,
            rows: newRows
        }));
    }
}

// 初始化WebSocket连接
function initWebSocket() {
    const wsProtocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
    const wsUrl = wsProtocol + window.location.host + '/ssh/' + serverId;

    terminal.write("WebSocket URL: " + wsUrl + "\r\n");
    console.log("尝试连接WebSocket:", wsUrl);

    websocket = new WebSocket(wsUrl);

    websocket.onopen = function(event) {
        terminal.write("WebSocket连接已建立\r\n");
        console.log("WebSocket连接已建立");
        terminal.write("正在连接到 " + serverHost + ":" + serverPort + "...\r\n");
        // 连接建立后适配终端尺寸并同步到后端
        setTimeout(fitTerminal, 200);
    };

    websocket.onmessage = function(event) {
        console.log("收到WebSocket消息:", event.data);
        if (typeof event.data === 'string') {
            try {
                // 尝试解析JSON消息
                const message = JSON.parse(event.data);
                if (message.type === 'terminal') {
                    // 终端输出消息
                    terminal.write(message.content);
                    // 如果收到连接成功的消息，设置connected为true
                    if (message.content && message.content.startsWith('Connected to ')) {
                        connected = true;
                    }
                } else if (message.type === 'fileList') {
                    // 文件列表消息
                    handleRemoteFileList(message.files);
                } else if (message.type === 'error') {
                    // 错误消息
                    terminal.write("\r\n\x1b[31m" + message.content + "\x1b[0m\r\n");
                } else {
                    // 其他类型消息按普通文本处理
                    terminal.write(event.data);
                }
            } catch (e) {
                // 不是JSON格式，按普通文本处理
                terminal.write(event.data);
                // 如果收到连接成功的消息，设置connected为true
                if (event.data && event.data.startsWith('Connected to ')) {
                    connected = true;
                }
            }
        } else {
            // 处理非字符串类型的消息
            terminal.write("[非文本消息]\r\n");
        }
    };

    websocket.onclose = function(event) {
        connected = false;
        terminal.write("\r\n\x1b[31m连接已关闭\x1b[0m\r\n");
        terminal.write("关闭代码: " + event.code + "\r\n");
        terminal.write("关闭原因: " + event.reason + "\r\n");
        console.log("WebSocket连接关闭:", event.code, event.reason);
    };

    websocket.onerror = function(error) {
        terminal.write("\r\n\x1b[31mWebSocket错误: " + error + "\x1b[0m\r\n");
        console.error("WebSocket错误:", error);
    };
}

// 关闭Shell
function closeShell() {
    if (confirm('确定要关闭Shell连接吗？')) {
        if (websocket) {
            websocket.close();
        }
        window.close();
    }
}

// 设置拖拽上传
function setupDragAndDrop() {
    const dropZone = document.getElementById('dropZone');
    if (!dropZone) return;

    ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, preventDefaults, false);
        document.body.addEventListener(eventName, preventDefaults, false);
    });

    function preventDefaults(e) {
        e.preventDefault();
        e.stopPropagation();
    }

    ['dragenter', 'dragover'].forEach(eventName => {
        dropZone.addEventListener(eventName, highlight, false);
    });

    ['dragleave', 'drop'].forEach(eventName => {
        dropZone.addEventListener(eventName, unhighlight, false);
    });

    function highlight() {
        dropZone.classList.add('dragover');
    }

    function unhighlight() {
        dropZone.classList.remove('dragover');
    }

    dropZone.addEventListener('drop', handleDrop, false);

    function handleDrop(e) {
        const dt = e.dataTransfer;
        const files = dt.files;
        handleFiles(files);
    }

    function handleFiles(files) {
        if (files.length > 0) {
            selectedLocalFile = files[0];
            showUploadModal();
        }
    }
}

// 显示上传模态框
function showUploadModal() {
    if (!selectedLocalFile) {
        alert("请选择要上传的文件");
        return;
    }

    document.getElementById('remoteUploadPath').value = currentPath === "/" ? "/" + selectedLocalFile.name : currentPath + "/" + selectedLocalFile.name;
    document.getElementById('uploadModal').classList.add('show');
}

// 隐藏上传模态框
function hideUploadModal() {
    document.getElementById('uploadModal').classList.remove('show');
    selectedLocalFile = null;
    document.getElementById('localFile').value = '';
}

// 执行上传
function performUpload() {
    const fileInput = document.getElementById('localFile');
    const remotePathInput = document.getElementById('remoteUploadPath');

    if (!fileInput.files || fileInput.files.length === 0) {
        alert("请选择要上传的文件");
        return;
    }

    if (!remotePathInput.value) {
        alert("请输入远程保存路径");
        return;
    }

    selectedLocalFile = fileInput.files[0];
    const remotePath = remotePathInput.value;

    // 这里应该实现文件上传逻辑
    alert("文件上传功能待实现");
    hideUploadModal();
}
