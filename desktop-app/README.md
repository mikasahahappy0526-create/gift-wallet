# ギフトウォレット Desktop（Electron）

Android 版 WebView APK（`android-app`）と同じサイトをデスクトップで開くアプリです。  
`window.GiftWallet` を preload（contextIsolation + contextBridge）でページ読み込み前に公開するため、PC/スマホブラウザ向けのメンテナンス画面は表示されません。

- アプリ名: ギフトウォレット  
- App ID: `net.giftwallet.desktop`  
- ホーム URL: https://gift-wallet.pages.dev/

## 必要なもの

- Node.js 18 以上（推奨: 20 LTS）
- Windows 向け exe をこのマシン以外で作る場合も同様

## 開発実行（`npm start`）

```bash
cd desktop-app
npm install
npm start
```

起動すると Electron ウィンドウでギフトウォレットが開きます。

## Windows 向けビルド（portable exe）

Linux / macOS 上で Windows バイナリを出す場合は Wine 等が必要になることがあります。  
Windows 本体でビルドするのが確実です。

```bash
cd desktop-app
npm install
npm run build:win
```

成果物の例:

- `desktop-app/dist/gift-wallet-desktop.exe`（portable）

Windows ユーザーは **exe をダブルクリック**するだけで起動できます（インストール不要の portable 想定）。

リポジトリ直下へコピーする場合の例:

```bash
mkdir -p ../dist
cp dist/gift-wallet-desktop.exe ../dist/gift-wallet-desktop.exe
```

## Linux 向けビルド（アンパック）

```bash
cd desktop-app
npm install
npm run build:linux
```

成果物は `desktop-app/dist/linux-unpacked/` に出力されます。  
実行例: `./dist/linux-unpacked/gift-wallet-desktop`

## macOS 向けビルド

```bash
cd desktop-app
npm install
npm run build:mac
```

## Android / サイトとの関係

- 通常のブラウザでは `window.GiftWallet` が無いため、これまで通りメンテナンス画面が出ます。
- Android APK・本 Desktop アプリだけが `GiftWallet` ブリッジを持ち、アプリとしてサイトを利用できます。
- Desktop は Android `MainActivity.kt` のブリッジと注入 JS（`inject/*.js`）をミラーしています。

## 主なブリッジ API

| メソッド | 動作 |
|---|---|
| `showToast` | 通知 / 画面トースト |
| `getWalletBalance` / `setWalletBalance` | 残高の永続化（userData JSON） |
| `onCsvBase64` | CSV 取込 → 台帳へ |
| `openCushinQuick` | Cushin クイック引出を同一ウィンドウで開く |
| `startGiftWalletCsvSync` | no-op（Android と同様） |
| エラーユーザー ID 系 | 保存 / 取得 / クリア / パスワード |
| `copyText` | クリップボードへコピー |
| チャージ系 | no-op / stub（自動チャージ廃止） |

## トラブルシュート

- メンテナンス画面が出る → preload が読めていない可能性。`npm start` で起動しているか確認。
- CSV が取り込まれない → vaton の「ダウンロードする」後、ホームに戻って台帳を確認。
- ビルドで Wine エラー → Windows マシンで `npm run build:win` を実行してください。
