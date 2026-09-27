import Foundation

/// The wire enums of `POST /api/v1/reports` (`ReportModels.kt:21-29`, `ContentReportController.java:158-170`).
nonisolated enum ReportTargetType: String, Sendable { case video = "VIDEO", channel = "CHANNEL", playlist = "PLAYLIST" }
nonisolated enum ReportParentType: String, Sendable { case channel = "CHANNEL", playlist = "PLAYLIST" }
nonisolated enum ReportContentSubType: String, Sendable { case short = "SHORT", livestream = "LIVESTREAM" }

/// The only way to construct a report (ruling 66): every caller states the parent context
/// explicitly -- there is deliberately no shorter initializer.
nonisolated struct ReportContext: Equatable, Sendable {
    let targetType: ReportTargetType
    let targetId: String
    let parentType: ReportParentType?
    let parentId: String?
    let contentSubType: ReportContentSubType?

    init(targetType: ReportTargetType, targetId: String, parentType: ReportParentType?,
         parentId: String?, contentSubType: ReportContentSubType?) {
        self.targetType = targetType
        self.targetId = targetId
        self.parentType = parentType
        self.parentId = parentId
        self.contentSubType = contentSubType
    }
}

/// The 11 reasons in the sheet's visual order (`ContentReportBottomSheet.kt:98-113`); the raw
/// value is the backend's enum name (cross-checked against the generated `ReasonsPayloadPayload`).
nonisolated enum ReportReason: String, CaseIterable, Sendable {
    case music = "MUSIC"
    case nudity = "NUDITY"
    case badLanguage = "BAD_LANGUAGE"
    case flirting = "FLIRTING"
    case romance = "ROMANCE"
    case awrah = "AWRAH"
    case shirk = "SHIRK"
    case bidah = "BIDAH"
    case violence = "VIOLENCE"
    case misinformation = "MISINFORMATION"
    case other = "OTHER"

    /// `report_reason_*` -- pinned per case by `ReportPayloadTests.everyReasonHasACatalogKey`.
    var messageKey: String { "report_reason_" + rawValue.lowercased() }
}

/// The seven wire fields. The three parent fields are exactly what the generated client cannot
/// send (`api-specification.yaml:341-363` omits them) -- CF-C-7: delete this when the spec catches up.
nonisolated struct ReportBody: Encodable, Equatable, Sendable {
    let targetType: String
    let targetId: String
    let reasons: [String]
    let otherDescription: String?
    let parentType: String?
    let parentId: String?
    let contentSubType: String?
}

nonisolated enum ReportValidation: Error, Equatable, Sendable {
    case noReasons(messageKey: String)
    case tooManyReasons(messageKey: String)
}

nonisolated enum ReportPayload {
    /// `ContentReportController.java:161` validates `@Size(max = 10)` against an 11-value enum.
    static let maxReasons = 10
    static let maxOtherLength = 500

    static func make(context: ReportContext, reasons: [ReportReason], otherText: String?) -> Result<ReportBody, ReportValidation> {
        guard !reasons.isEmpty else { return .failure(.noReasons(messageKey: "report_select_reason")) }
        guard reasons.count <= maxReasons else { return .failure(.tooManyReasons(messageKey: "report_reason_limit")) }
        // ContentReportBottomSheet.kt:76 -- the description only travels with OTHER.
        let other = reasons.contains(.other)
            ? otherText.map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxOtherLength)) }
            : nil
        // ReportRepository.kt:41 / ContentReportService.java:83-93: a blank parentId drops the
        // whole parent context server-side; do it here instead, where it is tested.
        let parentId = context.parentId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasParent = !(parentId ?? "").isEmpty && context.parentType != nil
        return .success(ReportBody(
            targetType: context.targetType.rawValue,
            targetId: context.targetId,
            reasons: reasons.map(\.rawValue),
            otherDescription: (other ?? "").isEmpty ? nil : other,
            parentType: hasParent ? context.parentType?.rawValue : nil,
            parentId: hasParent ? parentId : nil,
            contentSubType: context.contentSubType?.rawValue))
    }
}

nonisolated enum ReportState: Equatable, Sendable {
    case idle
    case submitting
    case succeeded
    case rateLimited
    case failed(messageKey: String)

    /// What a reason-toggle flip does to the sheet's state: clears a shown error back to `.idle`,
    /// but never resurrects Submit mid-flight (Cubic #15) -- resetting `.submitting` re-enabled the
    /// button for a double-POST, with the first attempt's late `.succeeded` still able to dismiss
    /// the sheet over the second.
    static func afterReasonChange(_ current: ReportState) -> ReportState {
        current == .submitting ? .submitting : .idle
    }
}
