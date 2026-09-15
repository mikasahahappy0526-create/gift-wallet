# ギフトウォレット Android アプリ

Chrome 単体では他サイトのボタンを自動で押せません。このアプリは WebView 内でギフトウォレットを開き、Cushin の「+ 新しいセッション」を自動タップします。ユーザースクリプトは不要です。

## 使い方

1. `app-debug.apk` を Android に入れる
2. 「提供元不明のアプリ」を許可してインストール
3. アプリを開き、ギフトウォレットの「クイック引き出し」をタップ
4. 未ログインなら Cushin にログイン
5. クイック引き出し画面で「+ 新しいセッション」が自動で押される

## ビルド

```bash
export ANDROID_HOME=/path/to/android-sdk
cd android-app
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`
