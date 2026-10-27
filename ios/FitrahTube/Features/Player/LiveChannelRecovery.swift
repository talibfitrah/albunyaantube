import Foundation

/// Spec §9.1 and D12: what to do after a live-channel trigger, given the backend's current lives (nil = no answer).
/// Pure; `PlayerViewModel` does the I/O and the waiting. One value per incident; `next` counts the waits.
nonisolated struct LiveChannelRecovery: Sendable {
    enum Trigger: Sendable { case failure, ended }
    enum Step: Equatable, Sendable { case switchTo(id: String, title: String), sameId, wait(seconds: Int), giveUp }

    static let backoff = [15, 30, 60, 120]
    /// D12: a multi-live channel gets up to 2 min for the ended show to restart under its title.
    static let titleWait: TimeInterval = 120
    static let giveUpAfter: TimeInterval = 600

    /// The incident's identity: a wait that wakes into another incident (or none) stops.
    let id = UUID()
    let currentId: String
    /// D12's match key: the backend's own title (`PlayerArgs.liveTitle`, I-P1), never the displayed one.
    let currentTitle: String?
    let startedAt: Date
    private var waits = 0
    /// Spec Revision 5: a failure with no backend answer gets ONE same-id try per incident before the waits.
    private var triedBlind = false
    /// Spec Revision 8 (stage 9 I18): an end or not-live (any `.ended`, including a same-id try that came back not live)
    /// makes the incident an end one for good: it never re-resolves the same id.
    private var ended = false
    /// Spec Revision 8: one same-id try per wake. Set by every `.sameId`, cleared only by a `.wait`, so a try that fails
    /// waits for the next wake instead of trying again at once.
    private var sameIdSpent = false

    init(currentId: String, currentTitle: String?, startedAt: Date) {
        self.currentId = currentId
        self.currentTitle = currentTitle
        self.startedAt = startedAt
    }

    /// `retryAfter` (stage 9 I13): the backend's 503 Retry-After, the shortest next wait.
    mutating func next(_ trigger: Trigger, lives: [LiveChannelLives.Live]?, now: Date, retryAfter: Int? = nil) -> Step {
        let step = decide(trigger, lives: lives, now: now, retryAfter: retryAfter)
        // Stage 9 I-C10.4 (Android stage 4 S2): the id goes into the thumbnail URL and the resolve; anything but a video id
        // is no answer.
        if case .switchTo(let id, _) = step, !TitlesClient.isVideoId(id) { return decide(trigger, lives: nil, now: now, retryAfter: nil) }
        return step
    }

    private mutating func decide(_ trigger: Trigger, lives: [LiveChannelLives.Live]?, now: Date, retryAfter: Int?) -> Step {
        if trigger == .ended { ended = true }
        let elapsed = now.timeIntervalSince(startedAt)
        var deadline = Self.giveUpAfter
        if let lives, !lives.isEmpty, !lives.contains(where: { $0.id == currentId }) {
            if lives.count == 1 { return .switchTo(id: lives[0].id, title: lives[0].title) }
            // I-P1: a blank backend title is no title, and matches no other live (not even a blank one).
            if let title = currentTitle, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               let same = lives.first(where: { $0.title == title }) { return .switchTo(id: same.id, title: same.title) }
            if elapsed >= Self.titleWait { return .switchTo(id: lives[0].id, title: lives[0].title) }
            deadline = Self.titleWait
        } else if !ended, !sameIdSpent, lives?.contains(where: { $0.id == currentId }) == true {
            sameIdSpent = true
            return .sameId
        } else if !ended, !sameIdSpent, lives == nil, !triedBlind {
            triedBlind = true
            sameIdSpent = true
            return .sameId
        }
        if elapsed >= Self.giveUpAfter { return .giveUp }
        defer { waits += 1; sameIdSpent = false }
        // Clamped, so the title wait ends at 2 min and the give-up lands at 10 min, not up to a backoff step later.
        let backoff = max(Self.backoff[min(waits, Self.backoff.count - 1)], retryAfter ?? 0)
        return .wait(seconds: min(backoff, Int((deadline - elapsed).rounded(.up))))
    }
}
