import Foundation
import Testing
@testable import InnerTubeKit

/// Spec R7, R9-R11 on the real `tv_samsung` captures (Task 8).
@Suite struct TVAdaptiveTests {
    private func formats(_ name: String) throws -> [AdaptiveFormat] {
        let url = try #require(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try PlayerResponseParser().adaptiveFormats(Data(contentsOf: url))
    }

    /// Every challenge answered with a marker that cannot be mistaken for its input.
    private func answers(_ c: TVAdaptive.Challenges) -> SolvedChallenges {
        SolvedChallenges(n: Dictionary(uniqueKeysWithValues: c.n.map { ($0, "N0" + String($0.reversed())) }),
                         s: Dictionary(uniqueKeysWithValues: c.s.map { ($0, "S0" + String($0.reversed())) }))
    }

    @Test func Ix7uzPm3cU8TakesEveryAvc1TopFirstAndThePlain140() throws {
        let selection = try #require(TVAdaptive.select(try formats("player-tv-Ix7uzPm3cU8")))
        #expect(selection.video.first?.itag == 137)
        #expect(selection.video.allSatisfy { $0.mimeType.contains("avc1") })
        #expect(Set(selection.video.map(\.itag)).count == selection.video.count)
        #expect(selection.audio.map(\.bitrate) == [132_171])
    }

    @Test func uZT3McCJHzQHasEightGroupsWithThePlainOriginalFirst() throws {
        let selection = try #require(TVAdaptive.select(try formats("player-tv-uZT3McCJHzQ")))
        #expect(selection.audio.count == 8)
        let original = try #require(selection.audio.first)
        #expect(original.audioIsDefault)
        #expect(original.bitrate == 131_603)
        #expect(!TVAdaptive.xtagKeys(original.xtags).contains("vb"))
        #expect(!TVAdaptive.xtagKeys(original.xtags).contains("drc"))
        #expect(selection.video.first?.height == 480)          // itag 135 is its avc1 top
    }

    /// R9 (revision 4): 360p (`uxeSkxtZU5I`) and 352p (`5RHQrhSFTVE`) tops would swap 360p for 360p: no selection.
    @Test(arguments: ["player-tv-uxeSkxtZU5I", "player-tv-5RHQrhSFTVE"])
    func nothingAbove360pMeansNoSelection(_ name: String) throws {
        #expect(TVAdaptive.select(try formats(name)) == nil)
    }

    @Test func aVerticalShortKeepsItsTopRenditions() throws {   // R9: no height clause
        let selection = try #require(TVAdaptive.select(try formats("player-tv-short-vertical")))
        let sizes = Set(selection.video.compactMap { f in f.width.flatMap { w in f.height.map { "\(w)x\($0)" } } })
        #expect(sizes.isSuperset(of: ["1080x1920", "720x1280"]))
    }

    @Test func xtagKeysDecodeTheCapturedVariants() {
        #expect(TVAdaptive.xtagKeys("CggKA2RyYxIBMQ") == ["drc"])
        #expect(TVAdaptive.xtagKeys("CgcKAnZiEgEx") == ["vb"])
        #expect(TVAdaptive.xtagKeys(nil).isEmpty)
        #expect(TVAdaptive.xtagKeys("!!!").isEmpty)
    }

    @Test func noByteRangesMeansNoSelection() throws {   // R7: a live stream never selects
        let stripped = try formats("player-tv-Ix7uzPm3cU8").map { var f = $0; f.indexRange = nil; return f }
        #expect(TVAdaptive.select(stripped) == nil)
    }

    @Test func drmFormatsAreDropped() throws {
        let drm = try formats("player-tv-Ix7uzPm3cU8").map { var f = $0; f.hasDRM = true; return f }
        #expect(TVAdaptive.select(drm) == nil)
    }

    @Test func aUrlFormCaptureHasOneNAndNoS() throws {
        let c = TVAdaptive.challenges(try #require(TVAdaptive.select(try formats("player-tv-Ix7uzPm3cU8"))))
        #expect(c.n.count == 1)
        #expect(c.n.first?.count == 16)
        #expect(c.s.isEmpty)
    }

    @Test func aCipheredCaptureHasOneNAndOneSPerSelectedFormat() throws {
        let selection = try #require(TVAdaptive.select(try formats("player-tv-uZT3McCJHzQ")))
        let c = TVAdaptive.challenges(selection)
        #expect(c.n.count == 1)
        #expect(c.s.count == selection.video.count + selection.audio.count)
    }

    @Test func theRewriteChangesOnlyNAndAppendsTheCipherSignature() throws {   // R11
        let selection = try #require(TVAdaptive.select(try formats("player-tv-uZT3McCJHzQ")))
        let format = try #require(selection.video.first)
        let url = try TVAdaptive.solvedURL(format, answers: answers(TVAdaptive.challenges(selection)))
        let cipherURL = try #require(format.signatureCipher?.split(separator: "&").first { $0.hasPrefix("url=") }?
            .dropFirst(4).removingPercentEncoding)
        let before = cipherURL.split(separator: "?")[1].split(separator: "&").map(String.init)
        let after = try #require(url.absoluteString.split(separator: "?").last).split(separator: "&").map(String.init)
        #expect(after.count == before.count + 1)
        for (old, new) in zip(before, after) where !old.hasPrefix("n=") { #expect(old == new) }
        #expect(after.last?.hasPrefix("sig=S0") == true)      // the captured cipher's sp is `sig`
        #expect(url.scheme == "https")
    }

    @Test func aMissingAnswerFailsTheRewrite() throws {
        let selection = try #require(TVAdaptive.select(try formats("player-tv-Ix7uzPm3cU8")))
        #expect(throws: TVAdaptive.RewriteError.missingAnswer) {
            try TVAdaptive.synthetic(selection, answers: SolvedChallenges(n: [:], s: [:]))
        }
    }

    @Test func aPlainHTTPURLIsRefused() throws {
        var format = try #require(try formats("player-tv-Ix7uzPm3cU8").first { $0.itag == 137 })
        format.url = format.url?.replacingOccurrences(of: "https://", with: "http://")
        let n = try #require(TVAdaptive.challenges(.init(video: [format], audio: [])).n.first)
        #expect(throws: TVAdaptive.RewriteError.notHTTPS) {
            try TVAdaptive.solvedURL(format, answers: SolvedChallenges(n: [n: "N0"], s: [:]))
        }
    }

    @Test func theModelCarriesLanguageNameDefaultAndCodecs() throws {
        let selection = try #require(TVAdaptive.select(try formats("player-tv-uZT3McCJHzQ")))
        let model = try TVAdaptive.synthetic(selection, answers: answers(TVAdaptive.challenges(selection)))
        #expect(model.audio.first?.isDefault == true)
        #expect(model.audio.dropFirst().allSatisfy { !$0.isDefault })
        #expect(model.audio.first?.language == "en-US")
        #expect(model.video.first?.codecs.hasPrefix("avc1.") == true)
        #expect(model.audio.first?.codecs.hasPrefix("mp4a.") == true)
        #expect((model.video + model.audio).allSatisfy { $0.url.absoluteString.contains("&sig=S0") })
    }
}
