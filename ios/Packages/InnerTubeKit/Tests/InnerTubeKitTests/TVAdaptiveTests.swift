import Foundation
import Testing
@testable import InnerTubeKit

/// Spec R7, R9-R11 on the real `tv_samsung` captures (Task 8).
private let syntheticRanges = #""initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"1000"},"contentLength":"5000""#
private let syntheticCipherURL = "url=https%3A%2F%2Fexample.test%2Fv%3Fn%3Dxyz%26ip%3D192.0.2.1"

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
        let c = TVAdaptive.challenges(selection)
        let solved = answers(c)
        let url = try TVAdaptive.solvedURL(format, answers: solved)
        let cipherURL = try #require(format.signatureCipher?.split(separator: "&").first { $0.hasPrefix("url=") }?
            .dropFirst(4).removingPercentEncoding)
        let before = cipherURL.split(separator: "?")[1].split(separator: "&").map(String.init)
        let after = try #require(url.absoluteString.split(separator: "?").last).split(separator: "&").map(String.init)
        #expect(after.count == before.count + 1)
        for (old, new) in zip(before, after) where !old.hasPrefix("n=") { #expect(old == new) }
        let n = try #require(c.n.first)
        let answer = try #require(solved.n[n])
        #expect(after.first { $0.hasPrefix("n=") } == "n=" + answer)
        #expect(after.last?.hasPrefix("sig=S0") == true)      // the captured cipher's sp is `sig`
        #expect(url.scheme == "https")
    }

    @Test func aUrlFormRewriteIsByteExactExceptN() throws {   // R11 on the url path; the sanitized ip survives
        let selection = try #require(TVAdaptive.select(try formats("player-tv-Ix7uzPm3cU8")))
        let format = try #require(selection.video.first)
        let c = TVAdaptive.challenges(selection)
        let solved = answers(c)
        let url = try TVAdaptive.solvedURL(format, answers: solved)
        let before = try #require(format.url).split(separator: "?")[1].split(separator: "&").map(String.init)
        let after = try #require(url.absoluteString.split(separator: "?").last).split(separator: "&").map(String.init)
        #expect(after.count == before.count)
        for (old, new) in zip(before, after) where !old.hasPrefix("n=") { #expect(old == new) }
        let n = try #require(c.n.first)
        let answer = try #require(solved.n[n])
        #expect(after.first { $0.hasPrefix("n=") } == "n=" + answer)
        #expect(after.contains("ip=192.0.2.1"))
    }

    // MARK: - Small synthetic formats, for the cases the captures do not cover

    private func parse(_ entries: String...) throws -> [AdaptiveFormat] {
        try PlayerResponseParser().adaptiveFormats(Data(#"{"streamingData":{"adaptiveFormats":[\#(entries.joined(separator: ","))]}}"#.utf8))
    }

    private func video(_ source: String = #""url":"https://example.test/v?n=xyz&ip=192.0.2.1""#, itag: Int = 137,
                       bitrate: Int = 4_000_000, codecs: String = #"\"avc1.640028\""#) -> String {
        #"{"itag":\#(itag),\#(source),"mimeType":"video/mp4; codecs=\#(codecs)","bitrate":\#(bitrate),"width":1920,"height":1080,\#(syntheticRanges)}"#
    }

    private func audio(bitrate: Int = 130_000, _ extra: String = "") -> String {
        #"{"itag":140,"url":"https://example.test/a?n=xyz","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":\#(bitrate),\#(syntheticRanges)\#(extra)}"#
    }

    @Test(arguments: [#""url":"""#, #""url":"?""#, #""signatureCipher":"s=abc&url=%3F""#])
    func anEmptyOrBareQueryURLFailsCleanly(_ source: String) throws {
        let format = try #require(try parse(video(source)).first)
        #expect(throws: TVAdaptive.RewriteError.self) {
            try TVAdaptive.solvedURL(format, answers: SolvedChallenges(n: [:], s: ["abc": "S0cba"]))
        }
    }

    @Test func anEmptyURLIsNotSelected() throws {
        #expect(TVAdaptive.select(try parse(video(#""url":"""#), audio())) == nil)
    }

    @Test func aBareEqualsCipherPairIsIgnored() throws {
        let selection = try #require(TVAdaptive.select(try parse(video(#""signatureCipher":"=&s=abc&\#(syntheticCipherURL)""#), audio())))
        #expect(TVAdaptive.challenges(selection) == .init(n: ["xyz"], s: ["abc"]))
    }

    /// R11: `sp` defaults to `signature` when absent or empty, and is percent-encoded so a decoded `&` adds no parameter.
    @Test(arguments: [("s=abc", "signature=S0cba"), ("s=abc&sp=", "signature=S0cba"), ("s=abc&sp=x%26y", "x%26y=S0cba")])
    func theSignatureParameterName(_ cipher: String, _ appended: String) throws {
        let format = try #require(try parse(video(#""signatureCipher":"\#(cipher)&\#(syntheticCipherURL)""#)).first)
        let url = try TVAdaptive.solvedURL(format, answers: SolvedChallenges(n: ["xyz": "N0zyx"], s: ["abc": "S0cba"]))
        #expect(url.absoluteString == "https://example.test/v?n=N0zyx&ip=192.0.2.1&" + appended)
    }

    @Test(arguments: ["s=&\(syntheticCipherURL)", "s=%ZZ&\(syntheticCipherURL)", syntheticCipherURL, "s=abc&url=", "s=abc&url=%ZZ"])
    func anEmptyOrUndecodableCipherFieldIsUnusable(_ cipher: String) throws {
        #expect(TVAdaptive.select(try parse(video(#""signatureCipher":"\#(cipher)""#), audio())) == nil)
    }

    @Test func oneUnusableFormatIsDroppedNotFatal() throws {
        let bad = try parse(video(#""signatureCipher":"s=abc&sp=sig""#, itag: 999, bitrate: 99_000_000))
        let selection = try #require(TVAdaptive.select(try formats("player-tv-Ix7uzPm3cU8") + bad))
        #expect(!selection.video.contains { $0.itag == 999 })
        _ = try TVAdaptive.synthetic(selection, answers: answers(TVAdaptive.challenges(selection)))
    }

    @Test func anUnquotedCodecsValueIsUnusable() throws {
        #expect(TVAdaptive.select(try parse(video(codecs: "avc1.640028"), audio())) == nil)
    }

    @Test(arguments: [(-1...740, 741...1000, 5000), (0...800, 741...1000, 5000), (0...740, 741...5000, 5000),
                      (0...740, 741...1000, 0), (0...740, 741...1000, (1 << 40) + 1)])
    func insaneByteRangesAreUnusable(_ initRange: ClosedRange<Int>, _ indexRange: ClosedRange<Int>, _ length: Int) throws {
        var bad = try #require(try parse(video()).first)
        bad.initRange = initRange
        bad.indexRange = indexRange
        bad.contentLength = length
        #expect(TVAdaptive.select([bad] + (try parse(audio()))) == nil)
    }

    @Test func aRepeatedItagKeepsItsFirstEntry() throws {
        let selection = try #require(TVAdaptive.select(try parse(video(bitrate: 1), video(bitrate: 2), audio())))
        #expect(selection.video.map(\.bitrate) == [1])
    }

    @Test func aVoiceBoostVariantLosesToThePlainOne() throws {   // R10 rule 2 alone
        let selection = try #require(TVAdaptive.select(try parse(video(), audio(bitrate: 1, #","xtags":"CgcKAnZiEgEx""#), audio(bitrate: 2))))
        #expect(selection.audio.map(\.bitrate) == [2])
    }

    @Test func isDrcOutranksADrcXtag() throws {   // R10 rule 1 before rule 2
        let selection = try #require(TVAdaptive.select(try parse(video(), audio(bitrate: 1, #","isDrc":true"#),
                                                                 audio(bitrate: 2, #","xtags":"CggKA2RyYxIBMQ""#))))
        #expect(selection.audio.map(\.bitrate) == [2])
    }

    @Test func theModelPassesTheByteRangesThrough() throws {
        let selection = try #require(TVAdaptive.select(try formats("player-tv-Ix7uzPm3cU8")))
        let model = try TVAdaptive.synthetic(selection, answers: answers(TVAdaptive.challenges(selection)))
        for (rendition, format) in zip(model.video + model.audio, selection.video + selection.audio) {
            #expect(rendition.initRange == format.initRange)
            #expect(rendition.indexRange == format.indexRange)
            #expect(rendition.contentLength == format.contentLength)
            #expect(rendition.bandwidth == format.bitrate)
        }
        #expect(model.video.count == selection.video.count && model.audio.count == selection.audio.count)
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
