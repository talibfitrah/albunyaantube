import Foundation

/// Browse is requested in English (`BrowseClient.send`, Android's `Localization.fromLocale(Locale.US)`),
/// so its pre-rendered counts and ages are English prose; these read them back into numbers the app
/// formats in ITS locale, the way the backend's numbers already are. nil = no number in the text.
enum EnglishCounts {
    /// "38K views" / "1.2M subscribers" / "12,345 views" / "1 video" / "No views", for the ONE `unit`
    /// asked for, or nil. The whole text must be that count: the stats slot a view count sits in
    /// also carries "1.2K watching", "12 waiting" and "Scheduled for 10/1/26", none of them views.
    static func count(_ text: String?, unit: String) -> Int64? {
        guard let text else { return nil }
        if text == "No \(unit)s" { return 0 }
        guard let match = text.wholeMatch(of: /([0-9][0-9,]*(?:\.[0-9]+)?)([KMB])? ([a-z]+)/),
              match.3 == unit || match.3 == unit + "s",
              let value = Double(match.1.replacingOccurrences(of: ",", with: "")) else { return nil }
        let scale: Double = switch match.2 { case "K": 1e3; case "M": 1e6; case "B": 1e9; default: 1 }
        return Int64((value * scale).rounded())
    }

    /// "2 hours ago" -> 120, "Streamed 3 days ago" -> 4320, "30 seconds ago" -> 0. Months and years
    /// are 30 and 365 days: YouTube's own text is already that coarse.
    static func minutesAgo(_ text: String?) -> Int? {
        guard let text,
              let match = text.firstMatch(of: /([0-9]+) (second|minute|hour|day|week|month|year)s? ago/),
              let n = Int(match.1) else { return nil }
        let day = 1440
        let unit = switch match.2 {
        case "minute": 1; case "hour": 60; case "day": day; case "week": 7 * day
        case "month": 30 * day; case "year": 365 * day; default: 0
        }
        return n * unit
    }
}

public extension VideoItem {
    var viewCount: Int64? { EnglishCounts.count(viewCountText, unit: "view") }
    /// From the exact instant when there is one (an Atom row, whose `publishedText` froze at write time).
    var uploadedMinutesAgo: Int? {
        if let publishedAt { return max(0, Int(Date().timeIntervalSince(publishedAt) / 60)) }
        return EnglishCounts.minutesAgo(publishedText)
    }
    var uploadedDaysAgo: Int? { uploadedMinutesAgo.map { $0 / 1440 } }
}

public extension ChannelHeader {
    var subscriberCount: Int64? { EnglishCounts.count(subscriberText, unit: "subscriber") }
}

public extension PlaylistTile {
    var itemCount: Int? { EnglishCounts.count(itemCountText, unit: "video").map { Int($0) } }
}
