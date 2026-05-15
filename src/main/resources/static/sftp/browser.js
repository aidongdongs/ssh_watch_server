// === SFTP 文件浏览器 ===

let currentPath = '/';
let selectedFile = null;

// WebSocket 上传相关
const CHUNK_SIZE = 512 * 1024; // 512KB
let wsUpload = null;
let currentFile = null;
let fileOffset = 0;
let chunkTimeout = null;

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

// ===== 页面初始化 =====
document.addEventListener('DOMContentLoaded', function() {
    loadList('/');
});

// ===== 文件列表加载 =====
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

// ===== 渲染文件列表 =====
function renderFiles(items) {
    var tbody = document.getElementById('fileListBody');
    tbody.innerHTML = '';

    // 如果不是根目录，显示 "返回上级" 行
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

        // 单击行选中/取消选中，双击目录进入
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

// ===== 选中状态更新 =====
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

// ===== 面包屑 =====
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

// ===== 左侧快捷导航 =====
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

// ===== 状态显示 =====
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

// ===== 上传 =====
function uploadFile() {
    if (wsUpload && wsUpload.readyState === WebSocket.OPEN) {
        alert('已有上传任务进行中，请等待完成');
        return;
    }

    var input = document.createElement('input');
    input.type = 'file';
    input.onchange = function() {
        var file = input.files[0];
        if (!file) return;

        // 检查同名文件
        var existingRows = document.querySelectorAll('#fileListBody tr.file-row');
        for (var i = 0; i < existingRows.length; i++) {
            if (existingRows[i].textContent.includes(file.name)) {
                if (!confirm('文件 "' + file.name + '" 已存在，是否覆盖？')) return;
                break;
            }
        }

        startWebSocketUpload(file);
    };
    input.click();
}

function startWebSocketUpload(file) {
    currentFile = file;
    fileOffset = 0;

    var protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
    wsUpload = new WebSocket(protocol + location.host + '/sftp/upload/' + serverId);

    wsUpload.onopen = function() {
        wsUpload.send(JSON.stringify({
            type: 'upload_start',
            fileName: file.name,
            fileSize: file.size,
            remotePath: currentPath + '/' + file.name
        }));
    };

    wsUpload.onmessage = function(event) {
        var msg = JSON.parse(event.data);
        switch (msg.type) {
            case 'upload_ready':
                showProgress(currentFile.name, 0);
                clearTimeout(chunkTimeout);
                sendNextChunk();
                break;
            case 'upload_progress':
                updateProgress(msg.percent);
                clearTimeout(chunkTimeout);
                sendNextChunk();
                break;
            case 'upload_complete':
                hideProgress();
                clearTimeout(chunkTimeout);
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

// ===== 进度条 =====
function showProgress(fileName, percent) {
    var el = document.getElementById('uploadProgress');
    el.style.display = 'block';
    document.getElementById('uploadFileName').textContent = '📤 ' + fileName;
    document.getElementById('uploadPercent').textContent = percent.toFixed(1) + '%';
    document.getElementById('uploadProgressBar').style.width = percent + '%';
}

function updateProgress(percent) {
    document.getElementById('uploadPercent').textContent = percent.toFixed(1) + '%';
    document.getElementById('uploadProgressBar').style.width = percent + '%';
}

function hideProgress() {
    document.getElementById('uploadProgress').style.display = 'none';
    document.getElementById('uploadProgressBar').style.width = '0%';
    document.getElementById('uploadPercent').textContent = '0%';
}

// ===== 下载 =====
function downloadFile(path) {
    if (!path && selectedFile) path = selectedFile.path;
    if (!path) return;
    window.location.href = '/sftp/api/' + serverId + '/download?path=' + encodeURIComponent(path);
}

// ===== 重命名 =====
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

// ===== 删除 =====
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

// ===== 新建文件夹 =====
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
