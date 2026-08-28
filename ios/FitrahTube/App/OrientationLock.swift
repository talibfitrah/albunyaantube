import UIKit

/// Portrait lock for the Shorts screen (spec 10, brief 9.5: ShortsPlayerFragment.kt:886-903 locks
/// portrait on resume and restores the previous policy on pause). SwiftUI exposes no per-view
/// orientation control, and UIHostingController's own override is not reachable from a NavigationStack
/// destination, so the supported-orientation callback is the only hook -- which lives on the app
/// delegate. This is the entire reason this app has one.
///
/// B5 note: ruling 42's fullscreen orientation behaviour wants this same hook -- `mask` is the one
/// writable seam; do not add a second mechanism.
@MainActor enum OrientationLock {
    private(set) static var mask: UIInterfaceOrientationMask = .allButUpsideDown

    static func lockPortrait() {
        mask = .portrait
        request(.portrait)
    }

    static func release() {
        mask = .allButUpsideDown
        request(.allButUpsideDown)   // hands control back to the device; does not force a rotation
    }

    private static func request(_ orientations: UIInterfaceOrientationMask) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: orientations))
        // ponytail: no error handler. A refused geometry request means the device stays where it is,
        // which is the pre-B4 behaviour -- degrading to "not locked" is correct, crashing is not.
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        MainActor.assumeIsolated { OrientationLock.mask }
    }
}
