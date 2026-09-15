import UIKit
import WebKit

final class WebViewController: UIViewController {
    static let homeURL = URL(string: "https://gift-wallet.pages.dev/")!
    static let homeURLNoSplash = URL(string: "https://gift-wallet.pages.dev/?nosplash=1")!
    static let cushinQuickURL = URL(string: "https://cushintools.net/dashboard/quick-withdraw#auto-new-session")!
    static let fixedCsvName = "ポイント履歴_今月.csv"

    private var webView: WKWebView!
    private let bridge = GiftWalletBridge()
    private var pendingCsvText: String?
    private var toastLabel: UILabel?
    private var pendingDownloadPath: URL?

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.03, green: 0.03, blue: 0.06, alpha: 1)
        setupWebView()
        bridge.host = self
        bridge.webView = webView
        webView.load(URLRequest(url: Self.homeURL))
    }

    private func setupWebView() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        if #available(iOS 14.0, *) {
            config.defaultWebpagePreferences.allowsContentJavaScript = true
        }

        let ucc = config.userContentController
        // CRITICAL: GiftWallet must exist atDocumentStart before page scripts run
        ucc.addUserScript(GiftWalletBridge.makeBridgeUserScript())
        ucc.add(bridge, name: GiftWalletBridge.messageHandlerName)

        let wv = WKWebView(frame: .zero, configuration: config)
        wv.translatesAutoresizingMaskIntoConstraints = false
        wv.navigationDelegate = self
        wv.uiDelegate = self
        wv.allowsBackForwardNavigationGestures = true
        wv.scrollView.contentInsetAdjustmentBehavior = .automatic
        wv.backgroundColor = view.backgroundColor
        wv.isOpaque = false
        if #available(iOS 16.4, *) {
            wv.isInspectable = true
        }

        view.addSubview(wv)
        NSLayoutConstraint.activate([
            wv.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            wv.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            wv.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            wv.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        webView = wv
    }

    // MARK: - Toast

    func showToast(_ message: String) {
        let msg = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !msg.isEmpty else { return }
        toastLabel?.superview?.removeFromSuperview()

        let container = UIView()
        container.translatesAutoresizingMaskIntoConstraints = false
        container.backgroundColor = UIColor(red: 0.08, green: 0.07, blue: 0.13, alpha: 0.94)
        container.layer.cornerRadius = 20
        container.layer.masksToBounds = true
        container.layer.borderWidth = 1
        container.layer.borderColor = UIColor(red: 0.83, green: 0.66, blue: 0.26, alpha: 0.4).cgColor

        let label = UILabel()
        label.text = msg
        label.textColor = UIColor(red: 0.96, green: 0.94, blue: 0.90, alpha: 1)
        label.font = .systemFont(ofSize: 13, weight: .bold)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(container)
        container.addSubview(label)
        NSLayoutConstraint.activate([
            container.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            container.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -56),
            container.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            container.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24),
            label.topAnchor.constraint(equalTo: container.topAnchor, constant: 10),
            label.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -10),
            label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            label.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16)
        ])
        toastLabel = label
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.6) { [weak self, weak container] in
            container?.removeFromSuperview()
            if self?.toastLabel === label { self?.toastLabel = nil }
        }
    }

    // MARK: - Bridge actions

    func openCushinQuick() {
        pendingCsvText = nil
        webView.load(URLRequest(url: Self.cushinQuickURL))
    }

    func handleCsvBase64(_ base64: String, filename: String) {
        guard let data = Data(base64Encoded: base64, options: [.ignoreUnknownCharacters]) else {
            showToast("CSVの読込に失敗しました")
            return
        }
        let text = Self.decodeCsvBytes(data)
        importCsvText(text, filename: filename.isEmpty ? Self.fixedCsvName : filename)
    }

    func importCsvText(_ text: String, filename: String = fixedCsvName) {
        _ = filename
        let payload = Self.jsonString(text)
        let nameJs = Self.jsonString(Self.fixedCsvName)
        let current = webView.url?.absoluteString ?? ""

        let tryImportJS = """
        (function(){try{if(typeof window.importGiftWalletCsvText==='function'){return window.importGiftWalletCsvText(\(payload),\(nameJs))?'1':'0';}return 'missing';}catch(e){return 'err';}})();
        """

        if current.contains("gift-wallet.pages.dev") {
            webView.evaluateJavaScript(tryImportJS) { [weak self] result, _ in
                guard let self else { return }
                let desc = String(describing: result ?? "")
                if desc.contains("1") {
                    self.bridge.markCsvSynced()
                    self.showToast("CSVを同期しました")
                } else {
                    self.pendingCsvText = text
                    self.webView.load(URLRequest(url: Self.homeURLNoSplash))
                }
            }
        } else {
            pendingCsvText = text
            webView.load(URLRequest(url: Self.homeURLNoSplash))
        }
    }

    private func maybeFlushPendingCsv(url: String?) {
        guard let pending = pendingCsvText else { return }
        guard let url, url.contains("gift-wallet.pages.dev") else { return }
        let payload = Self.jsonString(pending)
        let nameJs = Self.jsonString(Self.fixedCsvName)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in
            guard let self else { return }
            let js = """
            (function(){try{if(typeof window.importGiftWalletCsvText==='function'){return window.importGiftWalletCsvText(\(payload),\(nameJs))?'1':'0';}return 'missing';}catch(e){return 'err';}})();
            """
            self.webView.evaluateJavaScript(js) { [weak self] result, _ in
                let desc = String(describing: result ?? "")
                if desc.contains("1") {
                    self?.pendingCsvText = nil
                    self?.bridge.markCsvSynced()
                    self?.showToast("CSVを同期しました")
                }
            }
        }
    }

    // MARK: - Helper injection (mirrors MainActivity / Electron)

    private func maybeInjectHelpers(url: String?) {
        guard let url, !url.isEmpty else { return }
        if url.contains("cushintools.net") {
            let isQuick = url.contains("/dashboard/quick-withdraw") || url.contains("auto-new-session")
            evaluateBundledJS(named: isQuick ? "helper-full" : "helper-base")
        } else if url.contains("wallet.vaton.jp") {
            evaluateBundledJS(named: "vaton-csv")
            evaluateBundledJS(named: "vaton-balance")
        } else if url.contains("gift-wallet.pages.dev") {
            evaluateBundledJS(named: "home-inject")
        }
    }

    private func evaluateBundledJS(named name: String) {
        guard let js = GiftWalletBridge.loadBundledJS(named: name) else { return }
        webView.evaluateJavaScript(js, completionHandler: nil)
    }

    // MARK: - CSV decode (UTF-8 / Shift_JIS)

    static func decodeCsvBytes(_ bytes: Data) -> String {
        if bytes.count >= 3,
           bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF {
            return String(data: bytes.dropFirst(3), encoding: .utf8) ?? ""
        }
        if let utf8 = String(data: bytes, encoding: .utf8) {
            if utf8.contains("\u{FFFD}") {
                return decodeShiftJIS(bytes) ?? utf8
            }
            return utf8
        }
        return decodeShiftJIS(bytes) ?? ""
    }

    private static func decodeShiftJIS(_ bytes: Data) -> String? {
        let encodings: [UInt] = [
            CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.shiftJIS.rawValue)),
            CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.shiftJIS_X0213.rawValue)),
            CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.dosJapanese.rawValue))
        ]
        for enc in encodings {
            if let s = String(data: bytes, encoding: String.Encoding(rawValue: enc)) {
                return s
            }
        }
        return nil
    }

    static func jsonString(_ s: String) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: s, options: []),
              let out = String(data: data, encoding: .utf8) else {
            return "\"\""
        }
        return out
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        bridge.refreshNativeStateInJS()
    }

    fileprivate func consumeDownloadedCSV(at url: URL) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            defer { try? FileManager.default.removeItem(at: url) }
            guard let data = try? Data(contentsOf: url) else {
                DispatchQueue.main.async { self?.showToast("CSVの読込に失敗しました") }
                return
            }
            let text = Self.decodeCsvBytes(data)
            DispatchQueue.main.async {
                self?.importCsvText(text)
            }
        }
    }
}

// MARK: - WKNavigationDelegate

extension WebViewController: WKNavigationDelegate {
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        let s = url.absoluteString
        if s.hasPrefix("http://") || s.hasPrefix("https://") || s.hasPrefix("about:") {
            decisionHandler(.allow)
            return
        }
        decisionHandler(.cancel)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        maybeInjectHelpers(url: webView.url?.absoluteString)
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        maybeInjectHelpers(url: webView.url?.absoluteString)
        bridge.refreshNativeStateInJS()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        let url = webView.url?.absoluteString
        maybeInjectHelpers(url: url)
        bridge.refreshNativeStateInJS()
        maybeFlushPendingCsv(url: url)
    }

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        let resp = navigationResponse.response
        let mime = (resp.mimeType ?? "").lowercased()
        let name = resp.suggestedFilename ?? ""
        let urlStr = resp.url?.absoluteString ?? ""
        let isCsv = mime.contains("csv")
            || mime.contains("comma-separated")
            || name.lowercased().hasSuffix(".csv")
            || name.contains("ポイント")
            || (urlStr.contains("wallet.vaton.jp") && mime.contains("text"))
        if isCsv || name.lowercased().contains(".csv") {
            if #available(iOS 15.0, *) {
                decisionHandler(.download)
                return
            }
        }
        decisionHandler(.allow)
    }

    @available(iOS 15.0, *)
    func webView(_ webView: WKWebView, navigationResponse: WKNavigationResponse, didBecome download: WKDownload) {
        download.delegate = self
    }

    @available(iOS 15.0, *)
    func webView(_ webView: WKWebView, navigationAction: WKNavigationAction, didBecome download: WKDownload) {
        download.delegate = self
    }
}

// MARK: - WKDownloadDelegate (CSV capture)

@available(iOS 15.0, *)
extension WebViewController: WKDownloadDelegate {
    func download(_ download: WKDownload,
                  decideDestinationUsing response: URLResponse,
                  suggestedFilename: String,
                  completionHandler: @escaping (URL?) -> Void) {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("gw-csv-\(UUID().uuidString).csv")
        pendingDownloadPath = tmp
        completionHandler(tmp)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let path = pendingDownloadPath else {
            showToast("CSVの読込に失敗しました")
            return
        }
        pendingDownloadPath = nil
        consumeDownloadedCSV(at: path)
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        pendingDownloadPath = nil
        showToast("CSVダウンロードに失敗しました")
    }
}

// MARK: - WKUIDelegate

extension WebViewController: WKUIDelegate {
    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
            webView.load(URLRequest(url: url))
        }
        return nil
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        let ac = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        ac.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(ac, animated: true)
    }

    func webView(_ webView: WKWebView,
                 runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        let ac = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        ac.addAction(UIAlertAction(title: "キャンセル", style: .cancel) { _ in completionHandler(false) })
        ac.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(ac, animated: true)
    }
}
