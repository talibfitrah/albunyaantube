import Foundation

/// The pre-network address gate (`EmailShape.kt:9-15`, ported verbatim). Deliberately NOT an
/// RFC-5322 validator: it mirrors Firebase Auth's own minimum shape, so it can never reject an
/// address Firebase would have accepted — it only stops the ones Firebase would immediately reject,
/// which is what keeps malformed attempts from burning the IP-based throttle window that legitimate
/// users on flaky networks then hit.
///
/// One `@`, a non-empty local part, and a domain carrying a dot that neither opens nor closes it.
nonisolated enum EmailShape {
    static func isValid(_ address: String) -> Bool {
        guard let at = address.firstIndex(of: "@"),
              at != address.startIndex,                       // non-empty local part
              address.lastIndex(of: "@") == at,               // exactly one "@"
              at != address.index(before: address.endIndex)   // non-empty domain
        else { return false }
        let domain = address[address.index(after: at)...]
        return domain.contains(".") && !domain.hasPrefix(".") && !domain.hasSuffix(".")
    }
}
