(function() {
    // Prevent multiple injections
    if (window._debugConsoleLoaded) return;
    window._debugConsoleLoaded = true;

    // Create the UI
    const container = document.createElement('div');
    container.id = 'advanced-debug-console';
    container.style.position = 'fixed';
    container.style.bottom = '10px';
    container.style.left = '10px';
    container.style.width = '90vw';
    container.style.maxWidth = '400px';
    container.style.maxHeight = '50vh';
    container.style.backgroundColor = 'rgba(0, 0, 0, 0.85)';
    container.style.color = '#00ff00';
    container.style.fontFamily = 'monospace';
    container.style.fontSize = '12px';
    container.style.zIndex = '999999';
    container.style.borderRadius = '8px';
    container.style.display = 'flex';
    container.style.flexDirection = 'column';
    container.style.boxShadow = '0 4px 6px rgba(0,0,0,0.3)';
    container.style.overflow = 'hidden';
    container.style.transition = 'all 0.3s ease';

    // Header (Draggable)
    const header = document.createElement('div');
    header.style.backgroundColor = '#333';
    header.style.padding = '8px';
    header.style.cursor = 'move';
    header.style.display = 'flex';
    header.style.justifyContent = 'space-between';
    header.style.alignItems = 'center';
    header.style.userSelect = 'none';

    const title = document.createElement('span');
    title.textContent = '🚀 System Logs';
    title.style.fontWeight = 'bold';

    const controls = document.createElement('div');

    const minimizeBtn = document.createElement('button');
    minimizeBtn.textContent = '—';
    minimizeBtn.style.marginRight = '5px';

    const copyBtn = document.createElement('button');
    copyBtn.textContent = '📋';
    copyBtn.style.marginRight = '5px';

    const clearBtn = document.createElement('button');
    clearBtn.textContent = '🗑️';

    [minimizeBtn, copyBtn, clearBtn].forEach(btn => {
        btn.style.background = 'none';
        btn.style.border = 'none';
        btn.style.color = 'white';
        btn.style.cursor = 'pointer';
        btn.style.padding = '0 5px';
    });

    controls.appendChild(minimizeBtn);
    controls.appendChild(copyBtn);
    controls.appendChild(clearBtn);
    header.appendChild(title);
    header.appendChild(controls);

    // Log Area
    const logArea = document.createElement('div');
    logArea.id = 'debug-console-logs';
    logArea.style.flex = '1';
    logArea.style.overflowY = 'auto';
    logArea.style.padding = '8px';
    logArea.style.whiteSpace = 'pre-wrap';
    logArea.style.wordBreak = 'break-word';

    container.appendChild(header);
    container.appendChild(logArea);

    if (document.body) {
        document.body.appendChild(container);
    } else {
        document.addEventListener('DOMContentLoaded', () => {
            document.body.appendChild(container);
        });
    }

    // State
    let isMinimized = false;
    let logs = [];

    // Drag functionality
    let isDragging = false;
    let currentX;
    let currentY;
    let initialX;
    let initialY;
    let xOffset = 0;
    let yOffset = 0;

    header.addEventListener('mousedown', dragStart);
    header.addEventListener('touchstart', dragStart, {passive: false});
    document.addEventListener('mouseup', dragEnd);
    document.addEventListener('touchend', dragEnd);
    document.addEventListener('mousemove', drag);
    document.addEventListener('touchmove', drag, {passive: false});

    function dragStart(e) {
        if (e.target.tagName.toLowerCase() === 'button') return;
        if (e.type === 'touchstart') {
            initialX = e.touches[0].clientX - xOffset;
            initialY = e.touches[0].clientY - yOffset;
        } else {
            initialX = e.clientX - xOffset;
            initialY = e.clientY - yOffset;
        }
        isDragging = true;
    }

    function dragEnd(e) {
        initialX = currentX;
        initialY = currentY;
        isDragging = false;
    }

    function drag(e) {
        if (isDragging) {
            e.preventDefault();
            if (e.type === 'touchmove') {
                currentX = e.touches[0].clientX - initialX;
                currentY = e.touches[0].clientY - initialY;
            } else {
                currentX = e.clientX - initialX;
                currentY = e.clientY - initialY;
            }
            xOffset = currentX;
            yOffset = currentY;
            setTranslate(currentX, currentY, container);
        }
    }

    function setTranslate(xPos, yPos, el) {
        el.style.transform = `translate3d(${xPos}px, ${yPos}px, 0)`;
    }

    // Controls
    minimizeBtn.addEventListener('click', () => {
        isMinimized = !isMinimized;
        if (isMinimized) {
            container.style.height = '35px';
            logArea.style.display = 'none';
        } else {
            container.style.height = 'auto';
            container.style.maxHeight = '50vh';
            logArea.style.display = 'block';
        }
    });

    clearBtn.addEventListener('click', () => {
        logArea.innerHTML = '';
        logs = [];
    });

    copyBtn.addEventListener('click', () => {
        const textToCopy = logs.join('\n');
        navigator.clipboard.writeText(textToCopy).then(() => {
            alert('Logs copied to clipboard!');
        }).catch(err => {
            alert('Failed to copy: ' + err);
        });
    });

    // Override console functions
    const originalLog = console.log;
    const originalError = console.error;
    const originalWarn = console.warn;
    const originalInfo = console.info;

    function appendLog(message, type, color) {
        const time = new Date().toISOString().split('T')[1].split('.')[0];
        const logStr = `[${time}] [${type}] ${message}`;
        logs.push(logStr);

        const logEl = document.createElement('div');
        logEl.style.color = color;
        logEl.style.marginBottom = '4px';
        logEl.style.borderBottom = '1px solid #444';
        logEl.style.paddingBottom = '2px';
        logEl.textContent = logStr;

        logArea.appendChild(logEl);
        logArea.scrollTop = logArea.scrollHeight;

        // Keep only last 500 logs to prevent memory issues
        if (logs.length > 500) {
            logs.shift();
            logArea.removeChild(logArea.firstChild);
        }
    }

    function formatArgs(args) {
        return Array.from(args).map(arg => {
            if (typeof arg === 'object') {
                try {
                    return JSON.stringify(arg);
                } catch(e) {
                    return String(arg);
                }
            }
            return String(arg);
        }).join(' ');
    }

    console.log = function() {
        originalLog.apply(console, arguments);
        appendLog(formatArgs(arguments), 'INFO', '#00ff00');
    };

    console.error = function() {
        originalError.apply(console, arguments);
        appendLog(formatArgs(arguments), 'ERROR', '#ff5555');
    };

    console.warn = function() {
        originalWarn.apply(console, arguments);
        appendLog(formatArgs(arguments), 'WARN', '#ffff55');
    };

    console.info = function() {
        originalInfo.apply(console, arguments);
        appendLog(formatArgs(arguments), 'INFO', '#55ffff');
    };

    // Expose a function for Android to push logs to
    window.pushAndroidLog = function(message) {
        appendLog(message, 'ANDROID', '#ffaa00');
    };

    // Load offline background logs from Android when the console loads
    setTimeout(() => {
        if (window.AndroidBridge && typeof window.AndroidBridge.getBackgroundLogs === 'function') {
            try {
                const bgLogsStr = window.AndroidBridge.getBackgroundLogs();
                const bgLogs = JSON.parse(bgLogsStr);
                if (bgLogs && bgLogs.length > 0) {
                    appendLog(`--- LOADED ${bgLogs.length} OFFLINE BACKGROUND LOGS ---`, 'SYSTEM', '#ffffff');
                    bgLogs.forEach(log => {
                        appendLog(log, 'BACKGROUND', '#ffaa00');
                    });
                    appendLog('--- END OFFLINE LOGS ---', 'SYSTEM', '#ffffff');
                }
            } catch(e) {
                console.error("Failed to parse background logs: " + e.message);
            }
        }
    }, 1500); // Give bridge a moment to initialize

    // Global error handler
    window.addEventListener('error', function(e) {
        console.error('Uncaught Error: ' + e.message + ' at ' + e.filename + ':' + e.lineno);
    });

    // Unhandled promise rejections
    window.addEventListener('unhandledrejection', function(e) {
        console.error('Unhandled Rejection: ' + e.reason);
    });

    // Intercept fetch requests to log network activity
    const originalFetch = window.fetch;
    window.fetch = async function() {
        const url = arguments[0];
        const options = arguments[1] || {};
        const method = options.method || 'GET';

        console.log(`[NET] Fetch Start: ${method} ${url}`);

        try {
            const response = await originalFetch.apply(this, arguments);
            const clone = response.clone();
            console.log(`[NET] Fetch Success: ${response.status} ${url}`);

            // Try to log response body for debugging
            clone.text().then(text => {
                if(text.length < 500) {
                     // console.log(`[NET] Fetch Body: ${text}`);
                }
            }).catch(e => {});

            return response;
        } catch (error) {
            console.error(`[NET] Fetch Error: ${method} ${url} - ${error.message}`);
            throw error;
        }
    };

    console.log('🚀 Advanced Debug Console Initialized');
})();
