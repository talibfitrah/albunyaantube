import Foundation
import InnerTubeKit

/// Fetches and parses WebVTT cues for an auto-generated caption track (spec §10 Captions
/// paragraph; plan §6.5: `captionTracks[].baseUrl&fmt=vtt`). Manual tracks arrive as HLS
/// `SUBTITLES` renditions and use AVKit's stock subtitle menu -- this only serves the
/// InnerTubeKit-surfaced `captionTracks` (`kind=asr`). Reuses InnerTubeKit's own `HTTPTransport`
/// (already public, ephemeral, no cookies) rather than inventing a second transport seam --
/// same reasoning as `BackendAvailabilityGate`.
struct CaptionsProvider {
    /// One parsed WebVTT cue.
    struct Cue: Equatable {
        let start: TimeInterval
        let end: TimeInterval
        let text: String
    }

    private let transport: HTTPTransport

    init(transport: HTTPTransport = URLSessionTransport()) {
        self.transport = transport
    }

    /// Fetches `track.url` (already `&fmt=vtt`, per `CaptionTrack`'s doc) and parses its cues.
    /// `userAgent` is the resolve's own `Resolved.userAgent` (I4, B1 final review): sending no
    /// User-Agent let CFNetwork fill in its default, which leaks the app build and the iOS version
    /// to YouTube on every caption fetch -- and pairs a different UA with the same session the
    /// `player` call established (plan §6.3: the client context stays byte-identical across calls).
    func cues(for track: CaptionTrack, userAgent: String) async throws -> [Cue] {
        let request = HTTPRequest(method: "GET", url: track.url, headers: ["User-Agent": userAgent], body: nil)
        let response = try await transport.send(request)
        guard response.status == 200, let text = String(data: response.body, encoding: .utf8) else { return [] }
        return Self.parseVTT(text)
    }

    /// Minimal WebVTT parser: only lines containing the `-->` timing arrow start a cue, so the
    /// `WEBVTT` header, a cue-identifier line, and `NOTE`/`STYLE` blocks (none of which contain
    /// `-->`) are skipped for free -- no separate block-skipping logic needed. Handles both
    /// `hh:mm:ss.mmm --> hh:mm:ss.mmm` and `mm:ss.mmm --> mm:ss.mmm` timestamps, trailing cue
    /// settings after the end timestamp, multi-line cue text (joined with `\n`), and strips
    /// inline `<c>`/`<v Speaker>`-style tags from the text.
    static func parseVTT(_ text: String) -> [Cue] {
        // M5 (B1 final review): real `timedtext` payloads are CRLF-terminated, and
        // `components(separatedBy: .newlines)` splits on EACH of \r and \n -- so every CRLF became
        // an extra empty line, and the cue-text loop below (which stops at the first empty line)
        // truncated every multi-line cue to nothing. Normalise first, then split.
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: .newlines)
        var cues: [Cue] = []
        var index = 0
        while index < lines.count {
            defer { index += 1 }
            guard let range = lines[index].range(of: "-->") else { continue }
            let startRaw = lines[index][..<range.lowerBound].trimmingCharacters(in: .whitespaces)
            // Trailing cue settings (e.g. "align:start position:10%") ride after the end
            // timestamp on the same line, space-separated -- only the first token is the time.
            let endRaw = lines[index][range.upperBound...]
                .trimmingCharacters(in: .whitespaces)
                .split(separator: " ", maxSplits: 1)
                .first.map(String.init) ?? ""
            guard let start = parseTimestamp(startRaw), let end = parseTimestamp(endRaw) else { continue }

            index += 1
            var textLines: [String] = []
            while index < lines.count, !lines[index].isEmpty {
                textLines.append(stripTags(lines[index]))
                index += 1
            }
            cues.append(Cue(start: start, end: end, text: textLines.joined(separator: "\n")))
        }
        return cues
    }

    /// The cue whose `[start, end)` interval contains `time`, or `nil` between/before/after cues.
    static func activeCue(_ cues: [Cue], at time: TimeInterval) -> Cue? {
        cues.first { $0.start <= time && time < $0.end }
    }

    /// `hh:mm:ss.mmm` or `mm:ss.mmm`.
    private static func parseTimestamp(_ raw: String) -> TimeInterval? {
        let parts = raw.split(separator: ":")
        guard parts.count == 2 || parts.count == 3,
              let secondsPart = parts.last?.split(separator: ".", maxSplits: 1), secondsPart.count == 2,
              let seconds = Double(secondsPart[0]), let millis = Double(secondsPart[1]) else { return nil }
        let leading = parts.dropLast().map { Double($0) }
        guard leading.allSatisfy({ $0 != nil }) else { return nil }
        let values = leading.compactMap { $0 }
        var total = seconds + millis / 1000
        if values.count == 2 {
            total += values[0] * 3600 + values[1] * 60
        } else if values.count == 1 {
            total += values[0] * 60
        }
        return total
    }

    private static func stripTags(_ line: String) -> String {
        line.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
    }
}
