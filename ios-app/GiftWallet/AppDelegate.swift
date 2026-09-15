import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.rootViewController = WebViewController()
        window.tintColor = UIColor(red: 0.83, green: 0.66, blue: 0.26, alpha: 1)
        window.makeKeyAndVisible()
        self.window = window
        return true
    }
}
