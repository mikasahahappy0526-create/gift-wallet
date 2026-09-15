import Foundation
import UIKit
import WebKit

/// Native ↔ JS bridge mirroring desktop-app/preload.js and Android GiftWalletBridge.
/// Writes arrive via WKScriptMessageHandler; sync getters use window.__gwNativeState
/// refreshed by evaluateJavaScript / atDocumentStart injection.
final class GiftWalletBridge: NSObject, WKScriptMessageHandler {
    static let messageHandlerName = "GiftWallet"
    static let prefsSuiteHint = "gift_wallet" // logical name; uses UserDefaults.standard keys

    static let keyWalletBalance = "wallet_balance"
    static let keyErrorUserIds = "error_userids"
    static let keyLastCsvSyncMs = "last_csv_sync_ms"

    weak var webView: WKWebView?
    weak var host: WebViewController?

    // MARK: - State

    var walletBalance: String {
        get { UserDefaults.standard.string(forKey: Self.keyWalletBalance) ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: Self.keyWalletBalance) }
    }

    var errorUserIdsJson: String {
        get { UserDefaults.standard.string(forKey: Self.keyErrorUserIds) ?? "[]" }
        set { UserDefaults.standard.set(newValue, forKey: Self.keyErrorUserIds) }
    }

    // MARK: - atDocumentStart user script

    /// Builds GiftWallet + seeds __gwNativeState from UserDefaults so getters work before any async round-trip.
    static func makeBridgeUserScript() -> WKUserScript {
        let balance = (UserDefaults.standard.string(forKey: keyWalletBalance) ?? "")
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "")
        let errorsRaw = UserDefaults.standard.string(forKey: keyErrorUserIds) ?? "[]"
        // Embed as JSON string literal via JSONSerialization for safe escaping
        let errorsJsLiteral: String
        if let data = try? JSONSerialization.data(withJSONObject: [errorsRaw], options: []),
           let arr = String(data: data, encoding: .utf8),
           arr.count >= 2 {
            // ["..."] → take the quoted element
            errorsJsLiteral = String(arr.dropFirst().dropLast())
        } else {
            errorsJsLiteral = "\"[]\""
        }

        let bridgeBody = loadBundledJS(named: "giftwallet-bridge") ?? fallbackBridgeJS()
        let seed = """
        (function(){
          window.__gwNativeState = window.__gwNativeState || {};
          window.__gwNativeState.walletBalance = '\(balance)';
          window.__gwNativeState.errorUserIdsJson = \(errorsJsLiteral);
        })();
        """
        let source = seed + "\n" + bridgeBody
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: false)
    }

    static func loadBundledJS(named name: String) -> String? {
        if let url = Bundle.main.url(forResource: name, withExtension: "js", subdirectory: "Inject") {
            return try? String(contentsOf: url, encoding: .utf8)
        }
        if let url = Bundle.main.url(forResource: name, withExtension: "js") {
            return try? String(contentsOf: url, encoding: .utf8)
        }
        return nil
    }

    /// Minimal fallback if giftwallet-bridge.js is missing from the bundle.
    private static func fallbackBridgeJS() -> String {
        """
        (function(){
          if(window.__gwBridgeInstalled)return;window.__gwBridgeInstalled=true;
          window.__gwNativeState=window.__gwNativeState||{walletBalance:'',errorUserIdsJson:'[]'};
          function post(m,a){try{window.webkit.messageHandlers.GiftWallet.postMessage({method:String(m||''),args:a||[]});}catch(e){}}
          function s(v){return v==null?'':String(v);}
          window.GiftWallet={
            showToast:function(m){post('showToast',[s(m)]);},
            getWalletBalance:function(){return s(window.__gwNativeState.walletBalance);},
            setWalletBalance:function(p){var t=s(p);try{var d=t.replace(/\\D/g,'');if(d)window.__gwNativeState.walletBalance=d;}catch(e){}post('setWalletBalance',[t]);},
            onCsvBase64:function(b,f){post('onCsvBase64',[s(b),s(f)]);},
            openCushinQuick:function(){post('openCushinQuick',[]);},
            startGiftWalletCsvSync:function(){},
            saveErrorUserId:function(u,e,a){post('saveErrorUserId',[s(u),s(e),s(a)]);},
            getErrorUserIdsJson:function(){return s(window.__gwNativeState.errorUserIdsJson)||'[]';},
            clearErrorUserIds:function(){try{window.__gwNativeState.errorUserIdsJson='[]';}catch(e){}post('clearErrorUserIds',[]);},
            setErrorUserPassword:function(u,p){post('setErrorUserPassword',[s(u),s(p)]);},
            copyText:function(t){post('copyText',[s(t)]);},
            chargeGiftUrl:function(){},onChargeComplete:function(){},notifyChargeLoginRequired:function(){},
            isGiftUrlCharged:function(){return true;},
            clearChargeQueue:function(){post('clearChargeQueue',[]);},
            notifyCsvLoginRequired:function(){post('notifyCsvLoginRequired',[]);},
            notifyCsvSyncFailed:function(m){post('notifyCsvSyncFailed',[s(m)]);}
          };
        })();
        """
    }

    // MARK: - Push state into JS cache

    func refreshNativeStateInJS(completion: (() -> Void)? = nil) {
        guard let webView else {
            completion?()
            return
        }
        let bal = jsonStringLiteral(walletBalance)
        let err = jsonStringLiteral(errorUserIdsJson)
        let js = """
        (function(){
          try {
            window.__gwNativeState = window.__gwNativeState || {};
            window.__gwNativeState.walletBalance = \(bal);
            window.__gwNativeState.errorUserIdsJson = \(err);
          } catch (e) {}
        })();
        """
        webView.evaluateJavaScript(js) { _, _ in completion?() }
    }

    private func jsonStringLiteral(_ s: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: s, options: []),
              let out = String(data: data, encoding: .utf8) else {
            return "\"\""
        }
        return out
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == Self.messageHandlerName else { return }
        guard let body = message.body as? [String: Any],
              let method = body["method"] as? String else { return }
        let args = (body["args"] as? [Any]) ?? []

        DispatchQueue.main.async { [weak self] in
            self?.dispatch(method: method, args: args)
        }
    }

    private func dispatch(method: String, args: [Any]) {
        func arg(_ i: Int) -> String {
            guard i < args.count else { return "" }
            let v = args[i]
            if let s = v as? String { return s }
            if v is NSNull { return "" }
            return String(describing: v)
        }

        switch method {
        case "showToast":
            host?.showToast(arg(0))

        case "setWalletBalance":
            setWalletBalance(arg(0))

        case "onCsvBase64":
            host?.handleCsvBase64(arg(0), filename: arg(1))

        case "openCushinQuick":
            host?.openCushinQuick()

        case "saveErrorUserId":
            saveErrorUserId(userId: arg(0), error: arg(1), at: arg(2))

        case "clearErrorUserIds":
            clearErrorUserIds()

        case "setErrorUserPassword":
            setErrorUserPassword(userId: arg(0), password: arg(1))

        case "copyText":
            copyText(arg(0))

        case "clearChargeQueue":
            break // stub

        case "notifyCsvLoginRequired":
            host?.showToast("ギフトウォレットにログインしてください")

        case "notifyCsvSyncFailed":
            let msg = arg(0).trimmingCharacters(in: .whitespacesAndNewlines)
            host?.showToast(msg.isEmpty ? "CSVの自動取得に失敗しました" : msg)

        default:
            break
        }
    }

    // MARK: - Persistence helpers (mirror Android / Electron)

    private func setWalletBalance(_ points: String) {
        let cleaned = points.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.isEmpty || cleaned == "—" || cleaned == "-" { return }
        let digits = cleaned.filter(\.isNumber)
        guard !digits.isEmpty else { return }
        walletBalance = digits
        refreshNativeStateInJS()
    }

    private func loadErrorArray() -> [[String: Any]] {
        guard let data = errorUserIdsJson.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return arr
    }

    private func saveErrorArray(_ arr: [[String: Any]]) {
        if let data = try? JSONSerialization.data(withJSONObject: arr, options: []),
           let s = String(data: data, encoding: .utf8) {
            errorUserIdsJson = s
        } else {
            errorUserIdsJson = "[]"
        }
        refreshNativeStateInJS()
    }

    private func saveErrorUserId(userId: String, error: String, at: String) {
        let id = userId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        var arr = loadErrorArray()
        var found = -1
        var existingPassword = ""
        for (i, o) in arr.enumerated() {
            if (o["userId"] as? String) == id {
                found = i
                existingPassword = o["password"] as? String ?? ""
                break
            }
        }
        let iso: String
        if at.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let fmt = ISO8601DateFormatter()
            fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            iso = fmt.string(from: Date())
        } else {
            iso = at
        }
        let obj: [String: Any] = [
            "userId": id,
            "error": error,
            "at": iso,
            "password": existingPassword
        ]
        if found >= 0 {
            arr[found] = obj
        } else {
            arr.append(obj)
        }
        saveErrorArray(arr)
    }

    private func setErrorUserPassword(userId: String, password: String) {
        let id = userId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty else { return }
        var arr = loadErrorArray()
        var found = -1
        for (i, o) in arr.enumerated() {
            if (o["userId"] as? String) == id {
                found = i
                break
            }
        }
        if found >= 0 {
            var o = arr[found]
            o["password"] = password
            arr[found] = o
        } else {
            arr.append([
                "userId": id,
                "error": "",
                "at": "",
                "password": password
            ])
        }
        saveErrorArray(arr)
    }

    private func clearErrorUserIds() {
        errorUserIdsJson = "[]"
        refreshNativeStateInJS()
    }

    private func copyText(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        UIPasteboard.general.string = t
        host?.showToast("リンクをコピーしました")
    }

    func markCsvSynced() {
        UserDefaults.standard.set(Date().timeIntervalSince1970 * 1000, forKey: Self.keyLastCsvSyncMs)
    }
}
