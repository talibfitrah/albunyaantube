import Foundation
import Testing

/// Owner directive 2026-10-14: in no way is there ever a YouTube player in the iOS app -- the whole
/// point of the app is that people do not end up on YouTube. This extends the 2026-08-27 ruling
/// (no "Open in YouTube" control, nothing that hands the user off). The IFrame embed rung that used
/// to be rung 3 of the playback ladder is gone; this test is what keeps it gone.
///
/// It reads the real source tree, not a bundle copy: every text file under `ios/FitrahTube`
/// (sources AND resources) plus the `Sources` of every local package that links into the binary
/// (`InnerTubeKit` is the resolver). The test bundle reaches the tree through `#filePath`, the same
/// technique `LocalizationTests.referencedKeys` uses.
///
/// `WKWebView` is deliberately NOT banned: a later playback-hardening effort may need a hidden web
/// view that is never shown and never plays video. What is banned is anything that names a YouTube
/// player: the nocookie player host, the IFrame API script, an embed URL, the embed rung's view and
/// its bundled page.
@Suite(.perTest)
struct NoYouTubePlayerTests {
    private static let banned = ["youtube-nocookie", "iframe_api", "youtube.com/embed", "EmbedRung", "embed.html"]

    /// `YouTubeURLParser` recognises `youtube-nocookie.com` as a host of the links a user PASTES into
    /// Suggest Content, to pull a video/playlist/channel id out of them. It opens, renders and
    /// navigates to nothing (its own doc comment says so). Exactly this file, exactly this pattern.
    private static let allowed: [String: Set<String>] = [
        "FitrahTube/Features/Suggest/YouTubeURLParser.swift": ["youtube-nocookie"],
    ]

    @Test func theShippingSourcesAndResourcesNameNoYouTubePlayer() throws {
        let ios = URL(filePath: #filePath)              // …/ios/FitrahTubeTests/NoYouTubePlayerTests.swift
            .deletingLastPathComponent()                 // …/ios/FitrahTubeTests
            .deletingLastPathComponent()                 // …/ios
        var roots = [ios.appending(path: "FitrahTube", directoryHint: .isDirectory)]
        let packages = ios.appending(path: "Packages", directoryHint: .isDirectory)
        for package in try FileManager.default.contentsOfDirectory(at: packages, includingPropertiesForKeys: nil) {
            roots.append(package.appending(path: "Sources", directoryHint: .isDirectory))
        }

        var scanned = 0
        var offenders: [String] = []
        for root in roots {
            guard let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey]) else { continue }
            for case let url as URL in files {
                guard (try? url.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true,
                      let text = try? String(contentsOf: url, encoding: .utf8)   // mp4/png/etc. do not decode: skipped
                else { continue }
                scanned += 1
                let relative = String(url.path.dropFirst(ios.path.count + 1))
                for pattern in Self.banned where text.contains(pattern) {
                    if Self.allowed[relative]?.contains(pattern) == true { continue }
                    offenders.append("\(relative): \(pattern)")
                }
            }
        }

        // A scan that found nothing to read would pass for the wrong reason (the same net as
        // `LocalizationTests`' `referenced.count > 200`).
        #expect(scanned > 200, "only \(scanned) text files scanned -- the source tree was not found")
        #expect(offenders.isEmpty, "a YouTube player is back in the app: \(offenders.sorted())")
    }
}
