'use strict';

const {
  app,
  BrowserWindow,
  ipcMain,
  clipboard,
  Notification,
  session,
} = require('electron');
const fs = require('fs');
const path = require('path');
const { TextDecoder } = require('util');

const HOME_URL = 'https://gift-wallet.pages.dev/';
const HOME_URL_NOSPLASH = 'https://gift-wallet.pages.dev/?nosplash=1';
const CUSHIN_QUICK_URL =
  'https://cushintools.net/dashboard/quick-withdraw#auto-new-session';
const FIXED_CSV_NAME = 'ポイント履歴_今月.csv';

const INJECT_DIR = path.join(__dirname, 'inject');

function loadInject(name) {
  return fs.readFileSync(path.join(INJECT_DIR, name), 'utf8');
}

const HELPER_JS_BASE = loadInject('helper-base.js');
const HELPER_JS_FULL = loadInject('helper-full.js');
const HOME_INJECT_JS = loadInject('home-inject.js');
const VATON_CSV_JS = loadInject('vaton-csv.js');
const VATON_BALANCE_JS = loadInject('vaton-balance.js');

/** @type {BrowserWindow | null} */
let mainWindow = null;
/** @type {string | null} */
let pendingCsvText = null;
let storePath = '';

/** @type {{ walletBalance: string, errorUserIds: any[], lastCsvSyncMs: number }} */
let store = {
  walletBalance: '',
  errorUserIds: [],
  lastCsvSyncMs: 0,
};

function storeFile() {
  if (!storePath) storePath = path.join(app.getPath('userData'), 'gift-wallet-store.json');
  return storePath;
}

function loadStore() {
  try {
    const p = storeFile();
    if (fs.existsSync(p)) {
      const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
      store = {
        walletBalance: typeof raw.walletBalance === 'string' ? raw.walletBalance : '',
        errorUserIds: Array.isArray(raw.errorUserIds) ? raw.errorUserIds : [],
        lastCsvSyncMs: typeof raw.lastCsvSyncMs === 'number' ? raw.lastCsvSyncMs : 0,
      };
    }
  } catch (_) {
    /* ignore */
  }
}

function saveStore() {
  try {
    fs.writeFileSync(storeFile(), JSON.stringify(store, null, 2), 'utf8');
  } catch (_) {
    /* ignore */
  }
}

function showToast(message) {
  const msg = String(message || '').trim();
  if (!msg) return;
  try {
    if (Notification.isSupported()) {
      new Notification({ title: 'ギフトウォレット', body: msg }).show();
    }
  } catch (_) {
    /* ignore */
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    // Also surface as a brief in-page toast when possible
    const payload = JSON.stringify(msg);
    mainWindow.webContents
      .executeJavaScript(
        `(function(){try{var t=document.getElementById('gw-desktop-toast');if(t)t.remove();t=document.createElement('div');t.id='gw-desktop-toast';t.textContent=${payload};t.setAttribute('style','position:fixed;left:50%;bottom:72px;transform:translateX(-50%);z-index:2147483647;background:rgba(20,18,32,.94);color:#f5f0e6;padding:10px 16px;border-radius:999px;font:700 13px system-ui,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,.4);border:1px solid rgba(212,168,67,.4);pointer-events:none;max-width:80vw;text-align:center');(document.body||document.documentElement).appendChild(t);setTimeout(function(){try{t.remove()}catch(e){}},2600)}catch(e){}})();`,
        true
      )
      .catch(() => {});
  }
}

function decodeCsvBytes(buf) {
  const bytes = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  if (
    bytes.length >= 3 &&
    bytes[0] === 0xef &&
    bytes[1] === 0xbb &&
    bytes[2] === 0xbf
  ) {
    return bytes.slice(3).toString('utf8');
  }
  const utf8 = bytes.toString('utf8');
  if (utf8.includes('\uFFFD')) {
    try {
      return new TextDecoder('shift_jis').decode(bytes);
    } catch (_) {
      try {
        return new TextDecoder('windows-31j').decode(bytes);
      } catch (_2) {
        return utf8;
      }
    }
  }
  return utf8;
}

function markCsvSynced() {
  store.lastCsvSyncMs = Date.now();
  saveStore();
}

function importCsvText(text) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const wc = mainWindow.webContents;
  const current = wc.getURL() || '';
  const payload = JSON.stringify(text);
  const nameJs = JSON.stringify(FIXED_CSV_NAME);

  const tryImport = () =>
    wc.executeJavaScript(
      `(function(){try{if(typeof window.importGiftWalletCsvText==='function'){return window.importGiftWalletCsvText(${payload},${nameJs})?'1':'0';}return 'missing';}catch(e){return 'err';}})();`,
      true
    );

  if (current.includes('gift-wallet.pages.dev')) {
    tryImport()
      .then((result) => {
        if (result && String(result).includes('1')) {
          markCsvSynced();
          showToast('CSVを同期しました');
        } else {
          pendingCsvText = text;
          wc.loadURL(HOME_URL_NOSPLASH);
        }
      })
      .catch(() => {
        pendingCsvText = text;
        wc.loadURL(HOME_URL_NOSPLASH);
      });
  } else {
    pendingCsvText = text;
    wc.loadURL(HOME_URL_NOSPLASH);
  }
}

function maybeFlushPendingCsv(url) {
  const pending = pendingCsvText;
  if (!pending) return;
  if (!url || !url.includes('gift-wallet.pages.dev')) return;
  if (!mainWindow || mainWindow.isDestroyed()) return;
  const payload = JSON.stringify(pending);
  const nameJs = JSON.stringify(FIXED_CSV_NAME);
  setTimeout(() => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.webContents
      .executeJavaScript(
        `(function(){try{if(typeof window.importGiftWalletCsvText==='function'){return window.importGiftWalletCsvText(${payload},${nameJs})?'1':'0';}return 'missing';}catch(e){return 'err';}})();`,
        true
      )
      .then((result) => {
        if (result && String(result).includes('1')) {
          pendingCsvText = null;
          markCsvSynced();
          showToast('CSVを同期しました');
        }
      })
      .catch(() => {});
  }, 400);
}

function maybeInjectHelpers(url) {
  if (!mainWindow || mainWindow.isDestroyed() || !url) return;
  const wc = mainWindow.webContents;
  const run = (js) => {
    wc.executeJavaScript(js, true).catch(() => {});
  };
  if (url.includes('cushintools.net')) {
    const isQuick =
      url.includes('/dashboard/quick-withdraw') || url.includes('auto-new-session');
    run(isQuick ? HELPER_JS_FULL : HELPER_JS_BASE);
  } else if (url.includes('wallet.vaton.jp')) {
    run(VATON_CSV_JS);
    run(VATON_BALANCE_JS);
  } else if (url.includes('gift-wallet.pages.dev')) {
    run(HOME_INJECT_JS);
  }
}


function setupDownloadInterceptor() {
  // Intercept CSV downloads (non-blob). Blob/data handled by injected VATON_CSV_JS → onCsvBase64.
  session.defaultSession.on('will-download', (_event, item) => {
    const mime = (item.getMimeType() || '').toLowerCase();
    const filename = item.getFilename() || '';
    const url = item.getURL() || '';
    const isCsv =
      mime.includes('csv') ||
      mime.includes('comma-separated') ||
      /\.csv$/i.test(filename) ||
      filename.includes('ポイント') ||
      (url.includes('wallet.vaton.jp') && mime.includes('text'));

    if (!isCsv && !/\.csv/i.test(filename)) {
      return;
    }

    const tmp = path.join(
      app.getPath('temp'),
      `gw-csv-${Date.now()}-${Math.random().toString(36).slice(2)}.csv`
    );
    item.setSavePath(tmp);
    item.once('done', (_ev, state) => {
      if (state !== 'completed') {
        showToast('CSVダウンロードに失敗しました');
        return;
      }
      try {
        const bytes = fs.readFileSync(tmp);
        try {
          fs.unlinkSync(tmp);
        } catch (_) {}
        const csvText = decodeCsvBytes(bytes);
        importCsvText(csvText);
      } catch (_) {
        showToast('CSVの読込に失敗しました');
      }
    });
  });
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 420,
    height: 820,
    minWidth: 360,
    minHeight: 600,
    title: 'ギフトウォレット',
    backgroundColor: '#080810',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webviewTag: false,
      spellcheck: false,
    },
  });

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));

  const attachNavHooks = (wc) => {
    const onNav = (_e, url) => {
      maybeInjectHelpers(url || wc.getURL());
    };
    wc.on('did-navigate', onNav);
    wc.on('did-navigate-in-page', onNav);
    wc.on('did-finish-load', () => {
      const url = wc.getURL();
      maybeInjectHelpers(url);
      maybeFlushPendingCsv(url);
    });
    wc.on('did-start-navigation', (_e, url) => {
      // Early inject attempt (page may not be ready; did-finish-load retries)
      if (url) maybeInjectHelpers(url);
    });
  };

  attachNavHooks(mainWindow.webContents);

  mainWindow.loadURL(HOME_URL);

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

function registerIpc() {
  ipcMain.on('gw:showToast', (_e, message) => showToast(message));

  ipcMain.on('gw:getWalletBalance', (e) => {
    e.returnValue = store.walletBalance || '';
  });

  ipcMain.on('gw:setWalletBalance', (_e, points) => {
    try {
      const cleaned = String(points || '').trim();
      if (!cleaned || cleaned === '—' || cleaned === '-') return;
      const digits = cleaned.replace(/\D/g, '');
      if (!digits) return;
      store.walletBalance = digits;
      saveStore();
    } catch (_) {}
  });

  ipcMain.on('gw:onCsvBase64', (_e, base64, _filename) => {
    try {
      const bytes = Buffer.from(String(base64 || ''), 'base64');
      const text = decodeCsvBytes(bytes);
      importCsvText(text);
    } catch (_) {
      showToast('CSVの読込に失敗しました');
    }
  });

  ipcMain.on('gw:openCushinQuick', () => {
    pendingCsvText = null;
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.loadURL(CUSHIN_QUICK_URL);
    }
  });

  ipcMain.on('gw:saveErrorUserId', (_e, userId, error, at) => {
    try {
      const id = String(userId || '').trim();
      if (!id) return;
      const arr = store.errorUserIds;
      let found = -1;
      let existingPassword = '';
      for (let i = 0; i < arr.length; i++) {
        if (arr[i] && arr[i].userId === id) {
          found = i;
          existingPassword = arr[i].password || '';
          break;
        }
      }
      const obj = {
        userId: id,
        error: String(error || ''),
        at:
          String(at || '').trim() ||
          new Date().toISOString(),
        password: existingPassword,
      };
      if (found >= 0) arr[found] = obj;
      else arr.push(obj);
      store.errorUserIds = arr;
      saveStore();
    } catch (_) {}
  });

  ipcMain.on('gw:getErrorUserIdsJson', (e) => {
    try {
      e.returnValue = JSON.stringify(store.errorUserIds || []);
    } catch (_) {
      e.returnValue = '[]';
    }
  });

  ipcMain.on('gw:clearErrorUserIds', () => {
    store.errorUserIds = [];
    saveStore();
  });

  ipcMain.on('gw:setErrorUserPassword', (_e, userId, password) => {
    try {
      const id = String(userId || '').trim();
      if (!id) return;
      const arr = store.errorUserIds;
      let found = -1;
      for (let i = 0; i < arr.length; i++) {
        if (arr[i] && arr[i].userId === id) {
          found = i;
          break;
        }
      }
      if (found >= 0) {
        arr[found].password = String(password || '');
      } else {
        arr.push({
          userId: id,
          error: '',
          at: '',
          password: String(password || ''),
        });
      }
      store.errorUserIds = arr;
      saveStore();
    } catch (_) {}
  });

  ipcMain.on('gw:copyText', (_e, text) => {
    const t = String(text || '').trim();
    if (!t) return;
    try {
      clipboard.writeText(t);
      showToast('リンクをコピーしました');
    } catch (_) {}
  });

  ipcMain.on('gw:clearChargeQueue', () => {
    // no-op stub
  });

  ipcMain.on('gw:notifyCsvLoginRequired', () => {
    showToast('ギフトウォレットにログインしてください');
  });

  ipcMain.on('gw:notifyCsvSyncFailed', (_e, message) => {
    const msg = String(message || '').trim() || 'CSVの自動取得に失敗しました';
    showToast(msg);
  });
}

app.whenReady().then(() => {
  loadStore();
  registerIpc();
  setupDownloadInterceptor();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
