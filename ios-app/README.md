# ギフトウォレット iOS（WKWebView）

Android 版（`android-app`）および Electron Desktop（`desktop-app`）と同じサイトを iPhone / iPad で開くアプリです。  
`window.GiftWallet` を **WKUserScript（atDocumentStart）** でページ読み込み前に注入するため、ブラウザ向けのメンテナンス画面は表示されません。

- アプリ名: **ギフトウォレット**
- Bundle ID: `net.giftwallet.ios`
- 最低 OS: **iOS 15**
- ホーム URL: https://gift-wallet.pages.dev/

## 必要なもの

- macOS + **Xcode 15 以降**（推奨）
- Apple ID（無料の Personal Team でも実機インストール可）
- USB 接続できる iPhone（またはシミュレータ）

## Xcode で開いて実機 Run

1. Mac でこのリポジトリを clone / pull する
2. Finder または Xcode で次を開く:

```text
ios-app/GiftWallet.xcodeproj
```

3. 上部のターゲットが **GiftWallet** になっていることを確認
4. **Signing & Capabilities**
   - Team: 自分の Apple ID / 開発チームを選択
   - Bundle Identifier は `net.giftwallet.ios`（他アプリと衝突する場合は末尾だけ変更可）
5. 実行先に **自分の iPhone** を選ぶ（ケーブル接続 + 「このコンピュータを信頼」）
6. ▶️ Run（または `Cmd + R`）

初回は iPhone 側で「デベロッパを信頼」が必要な場合があります。

**設定 → 一般 → VPNとデバイス管理 → デベロッパ App → 信頼**

## シミュレータ

実機がなくても iOS Simulator で起動できます。Signing の Team は空でもシミュレータは動くことが多いです。

## TestFlight（配布）

1. [Apple Developer Program](https://developer.apple.com/) に加入（有料）
2. Xcode メニュー **Product → Archive**
3. Organizer で **Distribute App → App Store Connect**
4. App Store Connect でアプリを作成し、内部テスター / 外部テスターへ TestFlight 配信

App Icon は `GiftWallet/Assets.xcassets/AppIcon.appiconset` に 1024×1024 を追加してください（未設定でも Debug Run は可能です）。

## ブリッジ仕様（Desktop / Android 互換）

| メソッド | 動作 |
|---|---|
| `showToast` | 画面下部トースト |
| `getWalletBalance` / `setWalletBalance` | UserDefaults に永続化。getter は `window.__gwNativeState` の同期キャッシュ |
| `onCsvBase64` | CSV 取込 → `importGiftWalletCsvText`（必要なら `?nosplash=1` でホームへ） |
| `openCushinQuick` | Cushin クイック引出を同一 WebView で開く |
| `startGiftWalletCsvSync` | no-op |
| エラーユーザー ID 系 | 保存 / 取得 / クリア / パスワード（UserDefaults） |
| `copyText` | UIPasteboard |
| チャージ系 | no-op / stub（`isGiftUrlCharged` は常に `true`） |

同期 getter（`getWalletBalance` / `getErrorUserIdsJson`）は WKScriptMessageHandler が非同期のため、**atDocumentStart でシードした JS キャッシュ**を読みます。Native 側は変更後に `evaluateJavaScript` でキャッシュを更新します。

## 注入 JS

`GiftWallet/Inject/` は `desktop-app/inject/` のコピーに加え、iOS 用 `giftwallet-bridge.js` を含みます。  
ナビゲーション時のヘルパー注入は Android `MainActivity.kt` / Electron `main.js` と同じホスト判定です。

## トラブルシュート

- **メンテナンス画面が出る** → `GiftWallet` が atDocumentStart で入っていない。Inject の JS が Copy Bundle Resources に含まれているか確認。
- **CSV が入らない** → vaton で「ダウンロードする」後、ホームに戻り台帳を確認。blob 経由は `onCsvBase64`、通常 DL は `WKDownloadDelegate`。
- **署名エラー** → Team を選び直す / Bundle ID を一意にする / iPhone の「信頼」を確認。

## ディレクトリ

```text
ios-app/
  README.md
  GiftWallet.xcodeproj/
  GiftWallet/
    AppDelegate.swift
    WebViewController.swift
    GiftWalletBridge.swift
    Info.plist
    Assets.xcassets/
    Inject/
      giftwallet-bridge.js
      helper-base.js
      helper-full.js
      helper-quick.js
      home-inject.js
      vaton-balance.js
      vaton-csv.js
```
