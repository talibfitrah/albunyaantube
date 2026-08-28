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
    /// iPad keeps `.all` -- Info.plist declares upside-down for `~ipad` (M4, fix round 2). Note the
    /// lock is effectively iPhone-only: without `UIRequiresFullScreen` (deliberately absent), iPad
    /// multitasking ignores the mask entirely.
    private static var unlocked: UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown
    }
    private(set) static var mask: UIInterfaceOrientationMask = unlocked

    static func lockPortrait() {
        mask = .portrait
        request(.portrait)
    }

    static func release() {
        mask = unlocked
        request(unlocked)   // hands control back to the device; does not force a rotation
    }

    private static func request(_ orientations: UIInterfaceOrientationMask) {
        // The foreground-active scene, not `.first` (M5): with more than one connected scene the
        // first is not necessarily the one on screen.
        guard let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: orientations))
        // Task 4: the geometry request alone left the app portrait after Back with the device
        // still sideways; this is the API that makes UIKit re-read the mask and follow the device.
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
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
