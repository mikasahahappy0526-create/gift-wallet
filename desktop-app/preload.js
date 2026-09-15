'use strict';

const { contextBridge, ipcRenderer } = require('electron');

/**
 * Expose window.GiftWallet BEFORE page scripts run (contextIsolation + contextBridge).
 * Mirrors Android JavascriptInterface GiftWalletBridge in MainActivity.kt.
 */
contextBridge.exposeInMainWorld('GiftWallet', {
  showToast(message) {
    ipcRenderer.send('gw:showToast', String(message ?? ''));
  },

  getWalletBalance() {
    return ipcRenderer.sendSync('gw:getWalletBalance') || '';
  },

  setWalletBalance(points) {
    ipcRenderer.send('gw:setWalletBalance', String(points ?? ''));
  },

  onCsvBase64(base64, filename) {
    ipcRenderer.send('gw:onCsvBase64', String(base64 ?? ''), String(filename ?? ''));
  },

  openCushinQuick() {
    ipcRenderer.send('gw:openCushinQuick');
  },

  startGiftWalletCsvSync() {
    // no-op stub — matches Android
  },

  saveErrorUserId(userId, error, at) {
    ipcRenderer.send(
      'gw:saveErrorUserId',
      String(userId ?? ''),
      String(error ?? ''),
      String(at ?? '')
    );
  },

  getErrorUserIdsJson() {
    return ipcRenderer.sendSync('gw:getErrorUserIdsJson') || '[]';
  },

  clearErrorUserIds() {
    ipcRenderer.send('gw:clearErrorUserIds');
  },

  setErrorUserPassword(userId, password) {
    ipcRenderer.send(
      'gw:setErrorUserPassword',
      String(userId ?? ''),
      String(password ?? '')
    );
  },

  copyText(text) {
    ipcRenderer.send('gw:copyText', String(text ?? ''));
  },

  chargeGiftUrl(_url) {
    // no-op: auto-charge removed
  },

  onChargeComplete() {
    // no-op
  },

  notifyChargeLoginRequired() {
    // no-op
  },

  isGiftUrlCharged(_url) {
    return true;
  },

  clearChargeQueue() {
    ipcRenderer.send('gw:clearChargeQueue');
  },

  notifyCsvLoginRequired() {
    ipcRenderer.send('gw:notifyCsvLoginRequired');
  },

  notifyCsvSyncFailed(message) {
    ipcRenderer.send('gw:notifyCsvSyncFailed', String(message ?? ''));
  },
});
