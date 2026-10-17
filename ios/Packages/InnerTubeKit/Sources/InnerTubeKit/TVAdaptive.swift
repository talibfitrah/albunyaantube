import Foundation

/// Format selection, challenge collection and URL rewriting for the `tv_samsung` HD upgrade (spec R7, R9-R11). Pure.
enum TVAdaptive {
    /// Video: every distinct avc1 rendition, highest bitrate first. Audio: one rendition per group, the default group first.
    struct Selection: Equatable, Sendable {
        var video: [AdaptiveFormat]
        var audio: [AdaptiveFormat]
    }

    struct Challenges: Equatable, Sendable {
        var n: [String]
        var s: [String]
    }

    enum RewriteError: Error, Equatable {
        case missingAnswer
        case notHTTPS
    }

    /// R9: `video/mp4` avc1, either orientation, no height clause (above 1080p is VP9/AV1, dropped here). R10: `audio/mp4`
    /// mp4a grouped by `audioTrack.id`; per group the first of: not DRC, then no `vb`/`drc` xtag, then response order.
    /// Every rendition needs a usable URL or cipher, sane byte ranges, a quoted codecs value and no DRM, so a live stream
    /// (no byte ranges) never selects (R7), and one bad entry is dropped rather than failing the upgrade. R9 (revision 4):
    /// at least one avc1 rendition above 360p on its short side, or the swap would trade 360p for 360p and hide the rung-2
    /// pill. nil = nothing to play in HD.
    static func select(_ formats: [AdaptiveFormat]) -> Selection? {
        let usable = formats.filter { parts($0) != nil && rangesAreSane($0) && !codecs($0.mimeType).isEmpty && !$0.hasDRM }
        var seenItags = Set<Int>()
        let video = usable
            .filter { $0.mimeType.hasPrefix("video/mp4") && $0.mimeType.contains("avc1") && seenItags.insert($0.itag).inserted }
            .sorted { $0.bitrate > $1.bitrate }
        var groups: [(id: String?, members: [(index: Int, format: AdaptiveFormat)])] = []
        for (index, format) in usable.enumerated() where format.mimeType.hasPrefix("audio/mp4") && format.mimeType.contains("mp4a") {
            if let g = groups.firstIndex(where: { $0.id == format.audioTrackId }) {
                groups[g].members.append((index, format))
            } else {
                groups.append((format.audioTrackId, [(index, format)]))
            }
        }
        let ranked = groups.compactMap { group in group.members.min { rank($0) < rank($1) }?.format }
        let audio = ranked.filter(\.audioIsDefault) + ranked.filter { !$0.audioIsDefault }
        guard video.contains(where: { min($0.width ?? 0, $0.height ?? 0) > 360 }), !audio.isEmpty else { return nil }
        return Selection(video: video, audio: audio)
    }

    /// Non-negative, init before index, index inside a file of at most 2^40 bytes (which also makes the length positive).
    private static func rangesAreSane(_ f: AdaptiveFormat) -> Bool {
        guard let initRange = f.initRange, let indexRange = f.indexRange, let length = f.contentLength else { return false }
        return initRange.lowerBound >= 0 && initRange.upperBound < indexRange.lowerBound && indexRange.upperBound < length
            && length <= 1 << 40
    }

    private static func rank(_ member: (index: Int, format: AdaptiveFormat)) -> (Int, Int, Int) {
        let processed = xtagKeys(member.format.xtags).contains { $0 == "vb" || $0 == "drc" }
        return (member.format.isDrc ? 1 : 0, processed ? 1 : 0, member.index)
    }

    /// `xtags` is a base64url protobuf: repeated field 1 = {1: key, 2: value} (spec R10). The keys, in order.
    static func xtagKeys(_ xtags: String?) -> [String] {
        guard var text = xtags, !text.isEmpty else { return [] }
        text = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        text += String(repeating: "=", count: (4 - text.count % 4) % 4)
        guard let data = Data(base64Encoded: text) else { return [] }
        let bytes = [UInt8](data)
        var keys: [String] = []
        var i = 0
        while i + 1 < bytes.count, bytes[i] == 0x0a {
            let length = Int(bytes[i + 1])
            let entry = i + 2
            guard length < 128, length >= 2, entry + length <= bytes.count, bytes[entry] == 0x0a else { break }
            let keyLength = Int(bytes[entry + 1])
            guard entry + 2 + keyLength <= entry + length else { break }
            keys.append(String(decoding: bytes[(entry + 2)..<(entry + 2 + keyLength)], as: UTF8.self))
            i = entry + length
        }
        return keys
    }

    /// R11: the one adaptive `n` and one `s` per ciphered selected format, deduplicated, in order.
    static func challenges(_ selection: Selection) -> Challenges {
        var result = Challenges(n: [], s: [])
        for format in selection.video + selection.audio {
            guard let parts = parts(format) else { continue }
            if let n = parts.n, !result.n.contains(n) { result.n.append(n) }
            if let s = parts.s, !result.s.contains(s) { result.s.append(s) }
        }
        return result
    }

    /// The raw base URL (never re-encoded), its decoded `n`, and for a cipher its decoded `s` and `sp`. nil when the URL,
    /// or a cipher's `s`, is missing, empty or undecodable: that format cannot be played.
    private static func parts(_ format: AdaptiveFormat) -> (url: String, n: String?, s: String?, sp: String)? {
        if let url = format.url { return url.isEmpty ? nil : (url, queryValue("n", in: url), nil, "signature") }
        guard let cipher = format.signatureCipher else { return nil }
        var fields: [String: String] = [:]
        for pair in cipher.split(separator: "&") {
            // Not omitting empties: a bare `=` from the network must yield ["", ""], not an empty array to index.
            let kv = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
            if fields[kv[0]] == nil { fields[kv[0]] = (kv.count > 1 ? kv[1] : "").removingPercentEncoding ?? "" }
        }
        guard let url = fields["url"], !url.isEmpty, let s = fields["s"], !s.isEmpty else { return nil }
        return (url, queryValue("n", in: url), s, fields["sp"].flatMap { $0.isEmpty ? nil : $0 } ?? "signature")
    }

    private static func queryValue(_ name: String, in url: String) -> String? {
        guard let query = url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).dropFirst().first else {
            return nil
        }
        return query.split(separator: "&").first { $0.hasPrefix(name + "=") }
            .flatMap { String($0.dropFirst(name.count + 1)).removingPercentEncoding }
    }

    private static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~")

    private static func encoded(_ text: String) -> String {
        text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? text
    }

    /// R11: `n` replaced by its answer in place; a cipher's `url` gets `&<sp>=<answer>`; every other parameter
    /// byte-identical. The result must be `https`.
    static func solvedURL(_ format: AdaptiveFormat, answers: SolvedChallenges) throws -> URL {
        guard let parts = parts(format) else { throw RewriteError.missingAnswer }
        let pieces = parts.url.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        var items = pieces.count > 1 ? pieces[1].split(separator: "&", omittingEmptySubsequences: false).map(String.init) : []
        if let i = items.firstIndex(where: { $0.hasPrefix("n=") }) {
            guard let n = parts.n, let solved = answers.n[n] else { throw RewriteError.missingAnswer }
            items[i] = "n=" + encoded(solved)
        }
        if let s = parts.s {
            guard let solved = answers.s[s] else { throw RewriteError.missingAnswer }
            items.append(encoded(parts.sp) + "=" + encoded(solved))
        }
        guard let url = URL(string: pieces[0] + "?" + items.joined(separator: "&")), url.scheme == "https" else {
            throw RewriteError.notHTTPS
        }
        return url
    }

    /// The `SyntheticHLS` of an answered selection (R13). LANGUAGE is the track id's prefix, NAME its display name.
    static func synthetic(_ selection: Selection, answers: SolvedChallenges) throws -> SyntheticHLS {
        func rendition(_ f: AdaptiveFormat, isDefault: Bool) throws -> SyntheticHLS.Rendition {
            guard let initRange = f.initRange, let indexRange = f.indexRange, let length = f.contentLength else {
                throw RewriteError.missingAnswer      // `select` already required all three
            }
            return SyntheticHLS.Rendition(
                url: try solvedURL(f, answers: answers), codecs: codecs(f.mimeType), bandwidth: f.bitrate,
                averageBandwidth: f.averageBitrate, width: f.width, height: f.height, fps: f.fps,
                initRange: initRange, indexRange: indexRange, contentLength: length,
                language: f.audioTrackId.map { String($0.prefix { $0 != "." }) }, name: f.audioTrackName, isDefault: isDefault)
        }
        return SyntheticHLS(video: try selection.video.map { try rendition($0, isDefault: false) },
                            audio: try selection.audio.enumerated().map { try rendition($1, isDefault: $0 == 0) })
    }

    /// `video/mp4; codecs="avc1.640028"` -> `avc1.640028`; "" when unquoted or absent (`select` drops those).
    static func codecs(_ mimeType: String) -> String {
        guard let range = mimeType.range(of: "codecs=\"") else { return "" }
        return String(mimeType[range.upperBound...].prefix { $0 != "\"" })
    }
}
