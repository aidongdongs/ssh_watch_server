// === SFTP 文件浏览器：文件列表加载、导航、上传/下载/删除/重命名等操作 ===

let currentPath = '/';
let selectedFile = null;

// WebSocket 分块上传相关状态
const CHUNK_SIZE = 512 * 1024; // 每块 512KB，平衡网络吞吐与内存占用
let wsUpload = null;
let currentFile = null;
let fileOffset = 0;
let chunkTimeout = null; // 每块 30s 超时定时器

// 多文件上传队列状态
let uploadQueue = [];
let queueIndex = 0;
let queueTotalBytes = 0;
let queueUploadedBytes = 0;
let queueStartTime = 0;

// 单文件实时网速计算（每 500ms 采样一次）
let lastSpeedTime = 0;
let lastSpeedBytes = 0;
let currentSpeed = 0;

// 快捷导航目录
const QUICK_DIRS = [
    { label: '/',         path: '/' },
    { label: '/home',     path: '/home' },
    { label: '/etc',      path: '/etc' },
    { label: '/var/log',  path: '/var/log' },
    { label: '/tmp',      path: '/tmp' },
    { label: '/root',     path: '/root' },
    { label: '/opt',      path: '/opt' },
];

// ===== 页面初始化：默认加载根目录 =====
document.addEventListener('DOMContentLoaded', function() {
    loadList('/');
});

// ===== 文件列表加载：请求 API 后根据结果渲染表格/空状态/错误状态 =====
function loadList(path) {
    currentPath = path;
    selectedFile = null;
    showLoading();
    renderSidebar(path);

    fetch('/sftp/api/' + serverId + '/list?path=' + encodeURIComponent(path))
        .then(function(r) { return r.json(); })
        .then(function(json) {
            if (json.success) {
                if (json.data && json.data.length > 0) {
                    renderFiles(json.data);
                } else {
                    showEmpty();
                }
            } else {
                showError(json.message || '未知错误');
            }
            updateBreadcrumb(path);
            updateSelectionUI();
        })
        .catch(function(e) {
            showError('网络请求失败: ' + e.message);
            updateBreadcrumb(path);
        });
}

// ===== 渲染文件列表表格 =====
function renderFiles(items) {
    var tbody = document.getElementById('fileListBody');
    tbody.innerHTML = '';

    // 非根目录时添加 "返回上级" 行
    if (currentPath !== '/') {
        var parentTr = document.createElement('tr');
        parentTr.className = 'file-row';
        parentTr.innerHTML = '<td class="file-name dir">📁 ..</td><td class="file-size">-</td><td class="file-mtime">-</td><td class="file-perms">-</td>';
        parentTr.addEventListener('click', function() { goBack(); });
        tbody.appendChild(parentTr);
    }

    items.forEach(function(item) {
        var tr = document.createElement('tr');
        tr.className = 'file-row';

        var icon = item.isDir ? '📁' : '📄';
        var nameClass = item.isDir ? 'file-name dir' : 'file-name';
        tr.innerHTML = '<td class="' + nameClass + '">' + icon + ' ' + escapeHtml(item.name) + '</td>' +
            '<td class="file-size">' + formatSize(item.size) + '</td>' +
            '<td class="file-mtime">' + escapeHtml(item.lastModified) + '</td>' +
            '<td class="file-perms">' + escapeHtml(item.permissions) + '</td>';

        // 单击选中/取消选中行，双击进入目录
        tr.addEventListener('click', function(e) {
            e.stopPropagation();
            if (selectedFile && selectedFile.path === item.path) {
                selectedFile = null;
            } else {
                selectedFile = item;
            }
            updateSelectionUI();
        });

        tr.addEventListener('dblclick', function() {
            if (item.isDir) {
                navigateTo(item.path);
            }
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

// ===== 行选中状态更新 + 工具栏按钮 disabled 联动 =====
function updateSelectionUI() {
    var rows = document.querySelectorAll('#fileListBody tr');
    rows.forEach(function(tr) { tr.classList.remove('selected'); });

    if (selectedFile) {
        for (var i = 0; i < rows.length; i++) {
            if (rows[i].textContent.includes(selectedFile.name)) {
                rows[i].classList.add('selected');
                break;
            }
        }
    }

    document.getElementById('btnRename').disabled = !selectedFile;
    document.getElementById('btnDelete').disabled = !selectedFile;
    document.getElementById('btnDownload').disabled = !selectedFile || selectedFile.isDir;
}

// ===== 面包屑导航 =====
function updateBreadcrumb(path) {
    var container = document.getElementById('breadcrumb');
    container.innerHTML = '';

    var rootLink = document.createElement('a');
    rootLink.textContent = '📂  /';
    rootLink.addEventListener('click', function() { navigateTo('/'); });
    container.appendChild(rootLink);

    if (path === '/') return;

    var parts = path.split('/').filter(function(p) { return p !== ''; });
    var accumulated = '';
    parts.forEach(function(part) {
        accumulated += '/' + part;
        var sep = document.createElement('span');
        sep.className = 'sep';
        sep.textContent = '/';
        container.appendChild(sep);

        var link = document.createElement('a');
        link.textContent = part;
        link.addEventListener('click', function(p) {
            return function() { navigateTo(p); };
        }(accumulated));
        container.appendChild(link);
    });
}

// ===== 左侧快捷导航面板 =====
function renderSidebar(currentPath) {
    var sidebar = document.getElementById('sidebar');
    sidebar.innerHTML = '<div class="sidebar-title">📂 快捷目录</div>';

    QUICK_DIRS.forEach(function(dir) {
        var div = document.createElement('div');
        div.className = 'sidebar-item';
        if (dir.path === currentPath) {
            div.classList.add('active');
        }
        div.textContent = '📁 ' + dir.label;
        div.addEventListener('click', function() {
            navigateTo(dir.path);
        });
        sidebar.appendChild(div);
    });
}

// ===== 导航 =====
function navigateTo(path) {
    selectedFile = null;
    loadList(path);
}

function goBack() {
    if (currentPath === '/') return;
    var parent = currentPath.substring(0, currentPath.lastIndexOf('/'));
    if (parent === '') parent = '/';
    loadList(parent);
}

function refresh() {
    loadList(currentPath);
}

// ===== 四种视图状态：加载中 / 空目录 / 错误 / 正常 =====
function showLoading() {
    var tbody = document.getElementById('fileListBody');
    tbody.innerHTML = '<tr><td colspan="4"><div class="loading">🔄 加载中...</div></td></tr>';
}

function showEmpty() {
    var tbody = document.getElementById('fileListBody');
    tbody.innerHTML = '<tr><td colspan="4"><div class="empty">此目录为空</div></td></tr>';
}

function showError(msg) {
    var tbody = document.getElementById('fileListBody');
    tbody.innerHTML = '<tr><td colspan="4"><div class="error">❌ ' + escapeHtml(msg) + '</div></td></tr>';
}

// ===== 多文件上传：弹出文件选择器，启动 WebSocket 队列上传 =====
function uploadFiles() {
    if (wsUpload && wsUpload.readyState === WebSocket.OPEN) {
        alert('已有上传任务进行中，请等待完成');
        return;
    }

    var input = document.createElement('input');
    input.type = 'file';
    input.multiple = true;
    input.onchange = function() {
        var files = Array.from(input.files);
        if (files.length === 0) return;

        // 检查当前目录是否有同名文件
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

// ===== WebSocket 队列上传：建立连接后逐个文件发送 =====
function startQueueUpload(files) {
    uploadQueue = files;
    queueIndex = 0;
    queueTotalBytes = 0;
    queueUploadedBytes = 0;
    files.forEach(function(f) { queueTotalBytes += f.size; });

    showMultiProgress(files);
    queueStartTime = Date.now();

    var protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    wsUpload = new WebSocket(protocol + location.host + '/sftp/upload/' + serverId);

    wsUpload.onopen = function() {
        sendNextFileInQueue();
    };

    wsUpload.onmessage = onUploadMessage;

    wsUpload.onclose = function() {
        clearTimeout(chunkTimeout);
        wsUpload = null;
    };
}

// WebSocket 消息处理：upload_ready → 发数据块 → upload_progress → 下一块 → upload_complete
function onUploadMessage(event) {
    var msg = JSON.parse(event.data);
    switch (msg.type) {
        case 'upload_ready':
            clearTimeout(chunkTimeout);
            updateSingleFileProgress(queueIndex, 0, 0);
            sendNextChunk();
            break;
        case 'upload_progress':
            clearTimeout(chunkTimeout);
            updateSingleFileProgress(queueIndex, msg.percent, msg.received);
            sendNextChunk();
            break;
        case 'upload_complete':
            clearTimeout(chunkTimeout);
            markFileComplete(queueIndex);
            queueUploadedBytes += uploadQueue[queueIndex].size;
            queueIndex++;
            if (queueIndex < uploadQueue.length) {
                sendNextFileInQueue();
            }
            break;
        case 'upload_error':
            clearTimeout(chunkTimeout);
            markFileError(queueIndex, msg.message);
            queueUploadedBytes += uploadQueue[queueIndex].size;
            queueIndex++;
            if (queueIndex < uploadQueue.length) {
                sendNextFileInQueue();
            }
            break;
        case 'upload_queue_complete':
            hideMultiProgress();
            alert('全部上传完成: ' + msg.totalFiles + ' 个文件, ' + msg.speed);
            refresh();
            wsUpload.close();
            break;
    }
}

// 发送队列中的下一个文件（复用同一 WebSocket 连接）
function sendNextFileInQueue() {
    if (queueIndex >= uploadQueue.length) return;
    var file = uploadQueue[queueIndex];
    currentFile = file;
    fileOffset = 0;
    lastSpeedTime = Date.now();
    lastSpeedBytes = 0;
    currentSpeed = 0;

    wsUpload.send(JSON.stringify({
        type: 'upload_start',
        fileName: file.name,
        fileSize: file.size,
        remotePath: currentPath + '/' + (file.relativePath || file.name),
        queueIndex: queueIndex + 1,
        queueTotal: uploadQueue.length,
        queueTotalBytes: queueTotalBytes
    }));
}

// 取消全部上传
function cancelQueue() {
    if (!wsUpload) return;
    wsUpload.send(JSON.stringify({ type: 'upload_cancel' }));
    wsUpload.close();
    wsUpload = null;
    currentFile = null;
    clearTimeout(chunkTimeout);
    hideMultiProgress();
    refresh();
}

// ===== 多文件上传进度面板 =====
function showMultiProgress(files) {
    document.getElementById('uploadProgress').style.display = 'block';

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

// 更新单个文件的进度条和上传速度
function updateSingleFileProgress(index, percent, received) {
    var bar = document.getElementById('fileBar_' + index);
    var status = document.getElementById('fileStatus_' + index);
    if (bar) bar.style.width = percent + '%';
    if (status) {
        // 每 500ms 采样一次计算实时网速
        var now = Date.now();
        if (now - lastSpeedTime > 500 && received > 0) {
            var elapsed = (now - lastSpeedTime) / 1000;
            currentSpeed = (received - lastSpeedBytes) / elapsed;
            lastSpeedTime = now;
            lastSpeedBytes = received;
        }
        var text = percent.toFixed(1) + '%';
        if (currentSpeed > 0) {
            text += ' (' + formatSize(Math.round(currentSpeed)) + '/s)';
        }
        status.textContent = text;
    }
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
    document.getElementById('fileProgressList').innerHTML = '';
}

// ===== 滑动窗口流控：发一块 512KB，等待服务器确认后再发下一块 =====
function sendNextChunk() {
    if (!currentFile || fileOffset >= currentFile.size) return;

    chunkTimeout = setTimeout(function() {
        alert('上传超时：服务器长时间未响应，请检查网络后重试');
        if (wsUpload) wsUpload.close();
    }, 30000);

    var end = Math.min(fileOffset + CHUNK_SIZE, currentFile.size);
    var blob = currentFile.slice(fileOffset, end);
    fileOffset += blob.size;
    wsUpload.send(blob);
}

// ===== 下载：浏览器直接跳转，流式传输不缓冲到内存 =====
function downloadFile(path) {
    if (!path && selectedFile) path = selectedFile.path;
    if (!path) return;
    window.location.href = '/sftp/api/' + serverId + '/download?path=' + encodeURIComponent(path);
}

// ===== 重命名：选中文件 → prompt 输入新名称 → POST =====
function renameFile() {
    if (!selectedFile) return;
    var newName = prompt('重命名: ' + selectedFile.name, selectedFile.name);
    if (newName && newName !== selectedFile.name) {
        fetch('/sftp/api/' + serverId + '/rename', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({path: selectedFile.path, newName: newName})
        })
        .then(function(r) { return r.json(); })
        .then(function(json) {
            if (json.success) {
                selectedFile = null;
                refresh();
            } else {
                alert('重命名失败: ' + json.message);
            }
        });
    }
}

// ===== 删除：确认弹窗 → POST =====
function deleteFile() {
    if (!selectedFile) return;
    if (!confirm('确定要删除 "' + selectedFile.name + '" 吗？')) return;

    fetch('/sftp/api/' + serverId + '/delete', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({path: selectedFile.path, type: selectedFile.isDir ? 'dir' : 'file'})
    })
    .then(function(r) { return r.json(); })
    .then(function(json) {
        if (json.success) {
            selectedFile = null;
            refresh();
        } else {
            alert('删除失败: ' + json.message);
        }
    });
}

// ===== 新建文件夹：prompt 输入名称 → POST =====
function createDirectory() {
    var name = prompt('请输入文件夹名称:');
    if (!name || name.trim() === '') return;

    var newPath = (currentPath === '/' ? '/' : currentPath) + '/' + name.trim();
    fetch('/sftp/api/' + serverId + '/mkdir', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({path: newPath})
    })
    .then(function(r) { return r.json(); })
    .then(function(json) {
        if (json.success) {
            refresh();
        } else {
            alert('新建文件夹失败: ' + json.message);
        }
    });
}

// ===== 工具函数 =====
function formatSize(bytes) {
    if (bytes === 0) return '-';
    var units = ['B', 'KB', 'MB', 'GB', 'TB'];
    var i = Math.floor(Math.log(bytes) / Math.log(1024));
    if (i >= units.length) i = units.length - 1;
    return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
}

function escapeHtml(str) {
    if (!str) return '';
    var div = document.createElement('div');
    div.appendChild(document.createTextNode(str));
    return div.innerHTML;
}
