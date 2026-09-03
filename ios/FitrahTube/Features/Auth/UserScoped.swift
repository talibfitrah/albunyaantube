/// The seam Phase 1 promised but could not declare: `currentUserId` lives on the concrete stores
/// (`SwiftDataFavoritesStore`, `SwiftDataSubscriptionsStore`, `SwiftDataSavedPlaylistsStore`) but
/// not on their protocols, so the container — which hands out `any FavoritesStore` — could not set
/// it. One protocol, three conformances, zero behaviour change.
///
/// The three store protocols REFINE this rather than the three concrete classes adopting it: the
/// container's stored properties are `any FavoritesStore`/`any SavedPlaylistsStore`/
/// `any SubscriptionsStore`, so `userScopedStores` is only statically typed if the protocol carries
/// the requirement. Adopting it on the classes alone would make that list an `as?` cast that
/// silently drops a store the day one stops being the SwiftData class.
@MainActor protocol UserScoped: AnyObject {
    /// `""` is the anon/signed-out sentinel every store already defaults to.
    var currentUserId: String { get set }
}
