import Foundation
import Testing

/// Owner directive 2026-10-14: in no way is there ever a YouTube player in the iOS app -- the whole
/// point of the app is that people do not end up on YouTube. This extends the 2026-08-27 ruling
/// (no "Open in YouTube" control, nothing that hands the user off). The IFrame embed rung that used
/// to be rung 3 of the playback ladder is gone; this test is what keeps it gone.
///
/// It reads the real source tree, not a bundle copy: every text file under `ios/FitrahTube`
/// (sources AND resources) plus the `Sources` of every local package that links into the binary
/// (`InnerTubeKit` is the resolver), plus `ios/project.yml`, which can wire resources in from
/// elsewhere. The test bundle reaches the tree through `#filePath`, the same technique
/// `LocalizationTests.referencedKeys` uses.
///
/// `WKWebView` is deliberately NOT banned: a later playback-hardening effort may need a hidden web
/// view that is never shown and never plays video. What is banned is anything that names a YouTube
/// player, in any letter case: the nocookie player host, the IFrame API script and its legacy
/// `player_api` alias, the `YT.Player` constructor, an embed URL or `/embed/` path, the embed rung's
/// view and its bundled page.
@Suite(.perTest)
struct NoYouTubePlayerTests {
    /// Lower case: matched case-insensitively (URL hosts and paths are not case-sensitive).
    private static let banned = [
        "youtube-nocookie", "iframe_api", "player_api", "yt.player", "/embed/", "youtube.com/embed", "embedrung", "embed.html",
    ]

    /// `YouTubeURLParser` recognises `youtube-nocookie.com` as a host of the links a user PASTES into
    /// Suggest Content, to pull a video/playlist/channel id out of them. It opens, renders and
    /// navigates to nothing (its own doc comment says so). Exactly this file, exactly this pattern.
    private static let allowed: [String: Set<String>] = [
        "FitrahTube/Features/Suggest/YouTubeURLParser.swift": ["youtube-nocookie"],
    ]

    static func offenders(in text: String, at relative: String) -> [String] {
        let text = text.lowercased()
        return banned.filter { text.contains($0) && allowed[relative]?.contains($0) != true }.map { "\(relative): \($0)" }
    }

    @Test func theShippingSourcesAndResourcesNameNoYouTubePlayer() throws {
        let ios = URL(filePath: #filePath)              // …/ios/FitrahTubeTests/NoYouTubePlayerTests.swift
            .deletingLastPathComponent()                 // …/ios/FitrahTubeTests
            .deletingLastPathComponent()                 // …/ios
        var roots = [ios.appending(path: "FitrahTube", directoryHint: .isDirectory)]
        let packages = ios.appending(path: "Packages", directoryHint: .isDirectory)
        for package in try FileManager.default.contentsOfDirectory(at: packages, includingPropertiesForKeys: nil) {
            roots.append(package.appending(path: "Sources", directoryHint: .isDirectory))
        }
        var files = [ios.appending(path: "project.yml")]   // wires resources in from outside ios/FitrahTube
        for root in roots {
            guard let found = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey]) else { continue }
            files += found.compactMap { $0 as? URL }
        }

        var scanned: [String] = []
        var offenders: [String] = []
        for url in files {
            guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true,
                  let text = try? String(contentsOf: url, encoding: .utf8)   // mp4/png/etc. do not decode: skipped
            else { continue }
            let relative = String(url.path.dropFirst(ios.path.count + 1))
            scanned.append(relative)
            offenders += Self.offenders(in: text, at: relative)
        }

        // A scan that missed a root would pass for the wrong reason, so every root must contribute
        // (a renamed `Sources` makes the enumerator nil, which is skipped above).
        for root in ["FitrahTube/", "Packages/FitrahAPI/Sources/", "Packages/InnerTubeKit/Sources/"] {
            let contributed = scanned.contains { $0.hasPrefix(root) }
            #expect(contributed, "nothing scanned under ios/\(root) -- the root was not found")
        }
        let scannedProjectYML = scanned.contains("project.yml")
        #expect(scannedProjectYML, "ios/project.yml was not scanned")
        #expect(offenders.isEmpty, "a YouTube player is back in the app: \(offenders.sorted())")
    }

    /// Review M1: the legacy `player_api` alias, the `YT.Player` constructor, an embed path split
    /// from its host (`URLComponents` with `path = "/embed/…"`), and any letter case.
    @Test(arguments: [
        "new YT.Player('player', {})",
        "https://www.youtube.com/player_api",
        "components.path = \"/embed/\\(id)\"",
        "https://WWW.YOUTUBE.COM/EMBED/x",
        "struct embedRungView",
        "Bundle.main.url(forResource: \"Embed.HTML\", withExtension: nil)",
        "IFRAME_API",
        "https://www.YouTube-NoCookie.com/x",
    ])
    func theMatchCatchesEveryPlayerSpellingInAnyCase(_ planted: String) {
        #expect(!Self.offenders(in: planted, at: "FitrahTube/Planted.swift").isEmpty, "\(planted) must be caught")
    }

    /// The allow-list stays exact: the parser's one pattern, in the parser only.
    @Test func theAllowListIsExact() {
        let parser = "FitrahTube/Features/Suggest/YouTubeURLParser.swift"
        #expect(Self.offenders(in: "youtube-nocookie.com", at: parser).isEmpty)
        #expect(Self.offenders(in: "youtube-nocookie.com/embed/x", at: parser) == ["\(parser): /embed/"])
        #expect(!Self.offenders(in: "youtube-nocookie.com", at: "FitrahTube/Features/Suggest/Other.swift").isEmpty)
    }
}
