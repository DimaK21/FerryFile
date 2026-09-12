/* FerryFile — app.js
 * Vanilla JS, no dependencies.
 *
 * XSS safety: all user-visible text from the server (filenames, paths, error
 * messages) is set via element.textContent or element.setAttribute().
 * The only innerHTML assignments below use hard-coded SVG string literals
 * with no variable interpolation — this is intentional and safe.
 */

(function () {
  'use strict';

  // ── Static SVG icons (hard-coded literals, never interpolated) ─────────────

  var ICON_DIR  = '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M10 4H2v16h20V6H12l-2-2z"/></svg>';
  var ICON_FILE = '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6zm-1 1.5L18.5 9H13V3.5zM6 20V4h5v7h7v9H6z"/></svg>';

  // ── State ──────────────────────────────────────────────────────────────────

  var currentPath = '/';
  var sseSource   = null;
  var toastTimer  = null;
  var currentTransferId = null;
  var isInitialLoad = true;

  function newTransferId() {
    return 'tx-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
  }

  // ── DOM refs ───────────────────────────────────────────────────────────────

  var breadcrumbEl      = document.getElementById('breadcrumb');
  var fileListEl        = document.getElementById('file-list');
  var emptyEl           = document.getElementById('file-list-empty');
  var dropZoneEl        = document.getElementById('drop-zone');
  var uploadInput       = document.getElementById('upload-input');
  var rootHintEl        = document.getElementById('root-hint');
  var logoutBtn         = document.getElementById('logout-btn');
  var toastEl           = document.getElementById('toast');
  var progressContainer = document.getElementById('progress-container');
  var progressFilename  = document.getElementById('progress-filename');
  var progressPct       = document.getElementById('progress-pct');
  var progressFill      = document.getElementById('progress-fill');
  var progressDetails   = document.getElementById('progress-details');

  // ── Utility: toast notification ────────────────────────────────────────────

  function showToast(text, type) {
    if (toastTimer) clearTimeout(toastTimer);
    toastEl.textContent = text;   // safe: textContent only
    toastEl.className = 'toast visible' + (type ? ' ' + type : '');
    toastTimer = setTimeout(function () {
      toastEl.classList.remove('visible');
    }, 4000);
  }

  // ── Utility: format bytes ──────────────────────────────────────────────────

  function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    var k = 1024;
    var sizes = ['B', 'KB', 'MB', 'GB'];
    var i = Math.floor(Math.log(bytes) / Math.log(k));
    return (bytes / Math.pow(k, i)).toFixed(i === 0 ? 0 : 1) + ' ' + sizes[i];
  }

  // ── Utility: format date ───────────────────────────────────────────────────

  function formatDate(ms) {
    if (!ms) return '';
    return new Date(ms).toLocaleDateString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric'
    });
  }

  // ── Breadcrumb ─────────────────────────────────────────────────────────────

  function buildBreadcrumb(path) {
    while (breadcrumbEl.firstChild) {
      breadcrumbEl.removeChild(breadcrumbEl.firstChild);
    }

    var rootBtn = document.createElement('button');
    rootBtn.className = 'breadcrumb-item';
    rootBtn.textContent = 'Home';   // safe: textContent
    rootBtn.setAttribute('data-path', '/');
    rootBtn.addEventListener('click', function () { loadPath('/'); });
    breadcrumbEl.appendChild(rootBtn);

    if (path === '/') return;

    var parts = path.replace(/^\//, '').split('/').filter(Boolean);
    var accumulated = '';

    parts.forEach(function (part, idx) {
      accumulated += '/' + part;

      var sep = document.createElement('span');
      sep.className = 'breadcrumb-sep';
      sep.textContent = '/';
      breadcrumbEl.appendChild(sep);

      if (idx === parts.length - 1) {
        var cur = document.createElement('span');
        cur.className = 'breadcrumb-current';
        cur.textContent = part;     // safe: textContent
        breadcrumbEl.appendChild(cur);
      } else {
        var btn = document.createElement('button');
        btn.className = 'breadcrumb-item';
        btn.textContent = part;     // safe: textContent
        (function (p) {
          btn.setAttribute('data-path', p);
          btn.addEventListener('click', function () { loadPath(p); });
        }(accumulated));
        breadcrumbEl.appendChild(btn);
      }
    });
  }

  // ── File list rendering ────────────────────────────────────────────────────

  function renderItems(items) {
    var children = Array.prototype.slice.call(fileListEl.children);
    children.forEach(function (child) {
      if (child !== emptyEl) fileListEl.removeChild(child);
    });

    if (!items || items.length === 0) {
      emptyEl.textContent = 'This folder is empty';
      emptyEl.style.display = '';
      return;
    }

    emptyEl.style.display = 'none';

    items.slice().sort(function (a, b) {
      if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
      return a.name.localeCompare(b.name);
    }).forEach(function (item) {
      var row = document.createElement('div');
      row.className = 'file-item';

      // Icon — static literal SVG string, no variable interpolation
      var icon = document.createElement('span');
      icon.className = 'file-icon' + (item.isDirectory ? ' dir' : '');
      icon.innerHTML = item.isDirectory ? ICON_DIR : ICON_FILE;

      // Name button — XSS-safe: textContent only
      var nameBtn = document.createElement('button');
      nameBtn.className = 'file-name-btn' + (item.isDirectory ? ' dir' : '');
      nameBtn.textContent = item.name;       // safe: textContent
      nameBtn.setAttribute('data-path', item.path);

      if (item.isDirectory) {
        nameBtn.addEventListener('click', function () { loadPath(item.path); });
      } else {
        nameBtn.addEventListener('click', function () { downloadPaths([item.path]); });
      }

      // Meta
      var meta = document.createElement('span');
      meta.className = 'file-meta';
      var metaText = item.isDirectory ? '' : formatBytes(item.size);
      if (item.lastModified) {
        metaText += (metaText ? '  \xB7  ' : '') + formatDate(item.lastModified);
      }
      meta.textContent = metaText;           // safe: textContent

      row.appendChild(icon);
      row.appendChild(nameBtn);
      row.appendChild(meta);
      fileListEl.appendChild(row);
    });
  }

  // ── API: load directory ────────────────────────────────────────────────────

  function applyUploadVisibility(path) {
    var atRoot = path === '/';
    document.getElementById('upload-label').hidden = atRoot;
    dropZoneEl.hidden = atRoot;
    rootHintEl.hidden = !atRoot;
  }

  function loadPath(path) {
    currentPath = path;
    buildBreadcrumb(path);
    applyUploadVisibility(path);

    emptyEl.textContent = 'Loading…';
    emptyEl.style.display = '';

    var children = Array.prototype.slice.call(fileListEl.children);
    children.forEach(function (child) {
      if (child !== emptyEl) fileListEl.removeChild(child);
    });

    fetch('/api/list?path=' + encodeURIComponent(path))
      .then(function (res) {
        if (res.status === 401) { window.location.href = '/login'; return null; }
        if (!res.ok) throw new Error('Server error ' + res.status);
        return res.json();
      })
      .then(function (data) {
        if (!data) return;
        renderItems(data.items);

        if (isInitialLoad) {
          isInitialLoad = false;
          if (path === '/' && data.items.length === 1 && data.items[0].isDirectory) {
            loadPath(data.items[0].path);
            return;
          }
        }
      })
      .catch(function (err) {
        emptyEl.textContent = 'Failed to load folder contents';
        emptyEl.style.display = '';
        showToast('Load error: ' + err.message, 'error');
      });
  }

  // ── API: download file ─────────────────────────────────────────────────────

  function downloadPaths(paths) {
    if (!paths || paths.length === 0) return;
    var query = paths.map(function (p) {
      return 'path=' + encodeURIComponent(p);
    }).join('&');
    window.location.href = '/api/download?' + query;
  }

  // ── API: upload files ──────────────────────────────────────────────────────

  function handleUpload(files, path) {
    if (!files || files.length === 0) return;
    if (path === '/') {
      showToast('Open a folder first — the home screen only lists shared folders', 'error');
      return;
    }

    var transferId = newTransferId();
    var formData = new FormData();
    for (var i = 0; i < files.length; i++) {
      formData.append('file', files[i]);
    }

    connectSSE(transferId)
      .then(function () {
        return fetch(
          '/api/upload?path=' + encodeURIComponent(path) +
          '&transferId=' + encodeURIComponent(transferId),
          { method: 'POST', body: formData }
        );
      })
      .then(function (res) {
        if (res.status === 401) { window.location.href = '/login'; return null; }
        if (!res.ok) throw new Error('Upload failed: ' + res.status);
        // The `done` SSE event is the primary completion signal. But if SSE never
        // opened (the 1.5s fallback in connectSSE let the POST through anyway) or the
        // connection dropped mid-upload, this response is the only place we learn the
        // upload actually finished — parse it so that case still completes the transfer.
        return res.json().catch(function () { return null; });
      })
      .then(function (data) {
        if (data) completeTransfer(transferId, data.files, data.bytes);
      })
      .catch(function (err) {
        showToast('Upload error: ' + err.message, 'error');
        hideProgress();
        closeSse();
      });
  }

  // Runs the shared "transfer finished" UI update exactly once per transfer, whichever
  // of the two completion signals (SSE `done` event, HTTP response body) arrives first.
  // Clearing currentTransferId makes the other signal's matching check fail afterwards.
  function completeTransfer(transferId, files, bytes) {
    if (transferId !== currentTransferId) return;
    currentTransferId = null;

    progressFill.style.width = '100%';
    progressPct.textContent = '100%';

    var msg = files + ' file' + (files !== 1 ? 's' : '') + ' transferred';
    if (bytes != null) msg += ' (' + formatBytes(bytes) + ')';
    showToast(msg, 'success');

    setTimeout(function () {
      hideProgress();
      loadPath(currentPath);
    }, 800);
    closeSse();
  }

  // ── SSE progress ───────────────────────────────────────────────────────────

  function showProgress() {
    progressContainer.classList.add('visible');
  }

  function hideProgress() {
    progressContainer.classList.remove('visible');
    progressFill.style.width = '0%';
    progressFilename.textContent = 'Transferring…';
    progressPct.textContent = '0%';
    progressDetails.textContent = '';
  }

  function connectSSE(transferId) {
    closeSse();
    currentTransferId = transferId;
    showProgress();

    return new Promise(function (resolve) {
      var settled = false;
      function ready() {
        if (settled) return;
        settled = true;
        resolve();
      }

      sseSource = new EventSource('/api/progress');
      sseSource.onopen = ready;
      // Страховка: если браузер не сообщит об открытии, отправляем запрос всё равно.
      setTimeout(ready, 1500);

      sseSource.addEventListener('progress', function (e) {
        var data = parseEvent(e);
        if (!data || data.transferId !== currentTransferId) return;

        var pct = Math.min(100, Math.max(0, data.pct || 0));
        progressFill.style.width = pct + '%';
        progressPct.textContent = pct + '%';
        if (data.file) progressFilename.textContent = data.file;

        var details = '';
        if (data.bytes != null && data.total != null && data.total > 0) {
          details = formatBytes(data.bytes) + ' / ' + formatBytes(data.total);
        }
        if (typeof data.eta === 'number' && data.eta >= 0) {
          details += (details ? '  \xB7  ' : '') + 'ETA ' + data.eta + 's';
        }
        progressDetails.textContent = details;
      });

      sseSource.addEventListener('done', function (e) {
        var data = parseEvent(e);
        if (!data || data.transferId !== currentTransferId) return;
        completeTransfer(data.transferId, data.files, data.bytes);
      });

      sseSource.addEventListener('error', function (e) {
        var data = parseEvent(e);
        // EventSource dispatches its own connection-failure event under the same type
        // name "error", distinguishable from a real server-sent transfer error only by
        // the absence of parseable data — a connection blip is not a transfer failure.
        if (!data || data.transferId !== currentTransferId) return;
        showToast(data.message || 'Transfer error', 'error');
        hideProgress();
        closeSse();
      });

      sseSource.onerror = function () {
        if (sseSource && sseSource.readyState === EventSource.CLOSED) {
          hideProgress();
          sseSource = null;
        }
      };
    });
  }

  function parseEvent(e) {
    try {
      return JSON.parse(e.data);
    } catch (ex) {
      return null;
    }
  }

  function closeSse() {
    if (sseSource) { sseSource.close(); sseSource = null; }
  }

  // ── Drag-and-drop ──────────────────────────────────────────────────────────

  dropZoneEl.addEventListener('dragover', function (e) {
    e.preventDefault();
    dropZoneEl.classList.add('drag-over');
  });

  dropZoneEl.addEventListener('dragleave', function () {
    dropZoneEl.classList.remove('drag-over');
  });

  dropZoneEl.addEventListener('drop', function (e) {
    e.preventDefault();
    dropZoneEl.classList.remove('drag-over');
    var files = e.dataTransfer && e.dataTransfer.files;
    if (files && files.length > 0) handleUpload(files, currentPath);
  });

  // ── File input ─────────────────────────────────────────────────────────────

  uploadInput.addEventListener('change', function () {
    if (uploadInput.files && uploadInput.files.length > 0) {
      handleUpload(uploadInput.files, currentPath);
      uploadInput.value = '';
    }
  });

  // ── Logout ─────────────────────────────────────────────────────────────────

  logoutBtn.addEventListener('click', function () {
    fetch('/logout', { method: 'POST' })
      .then(function () { window.location.href = '/login'; })
      .catch(function () { window.location.href = '/login'; });
  });

  // ── Page unload ────────────────────────────────────────────────────────────

  window.addEventListener('beforeunload', closeSse);

  // ── Init ───────────────────────────────────────────────────────────────────

  loadPath('/');

}());
