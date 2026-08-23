import Foundation
import Observation

/// Android fetches the entire flat category list once and derives both the top-level and
/// subcategory views in memory instead of re-fetching per drill-down --
/// `docs/superpowers/plans/2026-08-23-ios-phase1-research/search-categories.md:347-359,384-412`.
/// RULINGS #27: sorted by `displayOrder` (nil last) then localized name. `topLevel()`/
/// `children(of:)` have no locale parameter (interface as specified), so the sort tiebreak uses
/// `Locale.current`; per-view display still resolves through `displayName(for:locale:)` with
/// the app's actually-resolved locale (RULINGS #26).
@MainActor protocol CategoriesCache: AnyObject, Observable {
    var all: [Category] { get }
    var isLoading: Bool { get }
    var error: Error? { get }
    func loadIfNeeded() async
    func reload() async
    func topLevel() -> [Category]
    func children(of id: String) -> [Category]
    func displayName(for id: String, locale: Locale) -> String?
}

@MainActor @Observable final class LiveCategoriesCache: CategoriesCache {
    private let client: any CatalogClient

    private(set) var all: [Category] = []
    private(set) var isLoading = false
    private(set) var error: Error?

    init(client: any CatalogClient) {
        self.client = client
    }

    func loadIfNeeded() async {
        guard all.isEmpty, !isLoading else { return }
        await fetch()
    }

    /// Same in-flight guard as `loadIfNeeded` (gate wave-2 W8): pull-to-refresh and the retry
    /// button could run two `fetch()`es at once, and the first to finish hid the skeleton while
    /// out-of-order completions left the older response in `all` (last writer wins).
    func reload() async {
        guard !isLoading else { return }
        await fetch()
    }

    func topLevel() -> [Category] {
        sorted(all.filter { $0.parentId == nil })
    }

    func children(of id: String) -> [Category] {
        sorted(all.filter { $0.parentId == id })
    }

    func displayName(for id: String, locale: Locale) -> String? {
        guard let category = all.first(where: { $0.id == id }) else { return nil }
        return Format.categoryDisplayName(category, locale: locale)
    }

    private func fetch() async {
        isLoading = true
        error = nil
        do {
            all = try await client.categories()
        } catch {
            // A cancelled `.task` (the view went away mid-fetch) is not a failure to show the
            // user (gate wave-2 W8) -- stored, it surfaced as a spurious error state the next time
            // the screen appeared, and `loadIfNeeded` no-ops once `all` is non-empty, so it stuck.
            //
            // `catch is CancellationError` could never fire (gate wave-4 V3): swift-openapi-runtime
            // wraps everything the transport throws in a `ClientError` before it reaches here, and
            // URLSession reports a cancelled request as `URLError(.cancelled)`, not
            // `CancellationError`. Asking the task covers the wrapped case whatever the payload is;
            // the two type checks cover a cancellation that did not come through this task.
            let isCancellation = Task.isCancelled || error is CancellationError
                || (error as? URLError)?.code == .cancelled
            if !isCancellation { self.error = error }
        }
        isLoading = false
    }

    private func sorted(_ categories: [Category]) -> [Category] {
        categories.sorted { lhs, rhs in
            let lhsOrder = lhs.displayOrder ?? .max
            let rhsOrder = rhs.displayOrder ?? .max
            if lhsOrder != rhsOrder { return lhsOrder < rhsOrder }
            // `localizedStandardCompare`, not `<` (gate A-M4): Swift's `<` on `String` is Unicode
            // code-point order, so Arabic names, Dutch `ij` and any accented Latin name sorted
            // wrongly -- including the common case where every `displayOrder` is nil, which ties
            // them all on `.max` and code-point-sorts the *entire* list. RULINGS #27 asks for
            // localized-name ordering.
            // ponytail: tiebreak collates with Locale.current; pass a locale into
            // topLevel()/children(of:) if the app locale must win over the system one.
            return Format.categoryDisplayName(lhs, locale: .current)
                .localizedStandardCompare(Format.categoryDisplayName(rhs, locale: .current)) == .orderedAscending
        }
    }
}
