/// The blocked/deleted terminal alert, as data: which two catalog keys a terminal event renders.
/// Pure and `nonisolated` so the mapping is testable without a scene — the `.alert` itself lives on
/// `RootView`, where the single button drops to guest and pops every tab to root.
///
/// `.signedOut` is not terminal and has no alert: it is the user's own sign-out, posted so
/// per-account holders can release state (`AccountStatusCenter.swift`). Returning nil for it is
/// what keeps `RootView`'s one consume path from popping a dialog on every sign-out.
nonisolated struct AccountStatusAlert: Equatable {
    let titleKey: String
    let bodyKey: String

    init?(_ event: AccountStatusEvent) {
        switch event {
        case .blocked: self.init(titleKey: "account_blocked_title", bodyKey: "account_blocked_body")
        case .deleted: self.init(titleKey: "account_deleted_title", bodyKey: "account_deleted_body")
        case .signedOut: return nil
        }
    }

    init(titleKey: String, bodyKey: String) {
        self.titleKey = titleKey
        self.bodyKey = bodyKey
    }
}
