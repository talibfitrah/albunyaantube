import UIKit

extension UIApplication {
    /// The key window, walked once (Stage 1 / B3c). Both federated providers need it — Apple as the
    /// `ASAuthorizationController` presentation anchor, Google as the presenting view controller —
    /// and each carried its own copy of the `connectedScenes -> UIWindowScene -> windows ->
    /// isKeyWindow` chain.
    ///
    /// Not `UIApplication.shared.windows` (deprecated and scene-unaware) and not `keyWindow` (same):
    /// on a multi-scene iPad the answer has to come from the connected scenes.
    @MainActor var fitrahKeyWindow: UIWindow? {
        connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
    }
}
