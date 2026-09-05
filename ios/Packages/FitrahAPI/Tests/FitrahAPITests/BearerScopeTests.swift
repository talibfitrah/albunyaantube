import Foundation
import Testing
@testable import FitrahAPI

/// Spec §8 / ruling F12: the Bearer never leaves the configured API host, and the rule has ONE
/// copy — `AuthorizedTransport` checks it against each
/// request URL, both through this.
@Suite struct BearerScopeTests {

    @Test func theBearerIsAllowedOnlyOnTheConfiguredHost() {
        #expect(BearerScope.allows(URL(string: "https://api.fitrahtube.com/api/v1/me")!, apiHost: "api.fitrahtube.com"))
        // The whole point: a redirect, a thumbnail CDN or an InnerTube URL never carries the token.
        #expect(!BearerScope.allows(URL(string: "https://www.youtube.com/watch?v=xc7keR2piUM")!, apiHost: "api.fitrahtube.com"))
        #expect(!BearerScope.allows(URL(string: "https://evil.api.fitrahtube.com/")!, apiHost: "api.fitrahtube.com"))
        #expect(!BearerScope.allows(URL(string: "/api/v1/me")!, apiHost: "api.fitrahtube.com"))
    }

    /// Scope is per-HOST: port and scheme are deliberately ignored
    /// (`FirebaseAuthInterceptor.kt:54-59`). Debug points at `http://localhost:8080/` and the app
    /// must still sign those requests, so a stricter rule would silently unsign every dev build.
    /// Host comparison is case-insensitive — DNS is, and `URL.host()` does not normalize case.
    @Test func theHostRuleIgnoresSchemeAndPortAndIsCaseInsensitive() {
        #expect(BearerScope.allows(URL(string: "http://localhost:8080/api/v1/me")!, apiHost: "localhost"))
        #expect(BearerScope.allows(URL(string: "https://localhost/api/v1/me")!, apiHost: "localhost"))
        #expect(BearerScope.allows(URL(string: "https://API.FitrahTube.com/api/v1/me")!, apiHost: "api.fitrahtube.com"))
        #expect(BearerScope.allows(URL(string: "https://api.fitrahtube.com/")!, apiHost: "API.FITRAHTUBE.COM"))
    }
}
