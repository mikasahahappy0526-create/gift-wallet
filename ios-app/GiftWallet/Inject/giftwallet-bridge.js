/**
 * Injected atDocumentStart via WKUserScript.
 * Defines window.GiftWallet BEFORE page scripts so maintenance overlay is skipped.
 * Sync getters read window.__gwNativeState (refreshed by native evaluateJavaScript).
 * Writes postMessage to webkit.messageHandlers.GiftWallet.
 */
(function () {
  if (window.__gwBridgeInstalled) return;
  window.__gwBridgeInstalled = true;

  window.__gwNativeState = window.__gwNativeState || {
    walletBalance: '',
    errorUserIdsJson: '[]'
  };

  function post(method, args) {
    try {
      if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.GiftWallet) {
        window.webkit.messageHandlers.GiftWallet.postMessage({
          method: String(method || ''),
          args: args || []
        });
      }
    } catch (e) {}
  }

  function str(v) {
    return v === undefined || v === null ? '' : String(v);
  }

  window.GiftWallet = {
    showToast: function (message) {
      post('showToast', [str(message)]);
    },

    getWalletBalance: function () {
      try {
        return str(window.__gwNativeState.walletBalance);
      } catch (e) {
        return '';
      }
    },

    setWalletBalance: function (points) {
      var p = str(points);
      try {
        var cleaned = p.trim();
        if (cleaned && cleaned !== '—' && cleaned !== '-') {
          var digits = cleaned.replace(/\D/g, '');
          if (digits) window.__gwNativeState.walletBalance = digits;
        }
      } catch (e) {}
      post('setWalletBalance', [p]);
    },

    onCsvBase64: function (base64, filename) {
      post('onCsvBase64', [str(base64), str(filename)]);
    },

    openCushinQuick: function () {
      post('openCushinQuick', []);
    },

    startGiftWalletCsvSync: function () {
      /* no-op stub — matches Android / Electron */
    },

    saveErrorUserId: function (userId, error, at) {
      post('saveErrorUserId', [str(userId), str(error), str(at)]);
    },

    getErrorUserIdsJson: function () {
      try {
        return str(window.__gwNativeState.errorUserIdsJson) || '[]';
      } catch (e) {
        return '[]';
      }
    },

    clearErrorUserIds: function () {
      try {
        window.__gwNativeState.errorUserIdsJson = '[]';
      } catch (e) {}
      post('clearErrorUserIds', []);
    },

    setErrorUserPassword: function (userId, password) {
      post('setErrorUserPassword', [str(userId), str(password)]);
    },

    copyText: function (text) {
      post('copyText', [str(text)]);
    },

    chargeGiftUrl: function (_url) {
      /* no-op: auto-charge removed */
    },

    onChargeComplete: function () {
      /* no-op */
    },

    notifyChargeLoginRequired: function () {
      /* no-op */
    },

    isGiftUrlCharged: function (_url) {
      return true;
    },

    clearChargeQueue: function () {
      post('clearChargeQueue', []);
    },

    notifyCsvLoginRequired: function () {
      post('notifyCsvLoginRequired', []);
    },

    notifyCsvSyncFailed: function (message) {
      post('notifyCsvSyncFailed', [str(message)]);
    }
  };
})();
