import FitrahAPI
import Foundation

nonisolated struct LiveCatalogClient: CatalogClient {
    private let client: Client

    init(client: Client) { self.client = client }

    func categories() async throws -> [Category] {
        let dtos = try await client.listPublicCategories(.init()).ok.body.json
        return dtos.map { dto in
            Category(id: dto.id, name: dto.name, slug: dto.slug, parentId: dto.parentId,
                      displayOrder: dto.displayOrder, localizedNames: dto.localizedNames?.additionalProperties, icon: dto.icon)
        }
    }

    func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
        let input = Operations.GetHomeFeed.Input(query: .init(
            cursor: cursor, categoryLimit: categoryLimit, contentLimit: contentLimit, category: category
        ))
        let output = try await client.getHomeFeed(input).ok.body.json
        let sections = (output.value2.data ?? []).map { dto in
            HomeSection(
                id: dto.id,
                name: dto.name ?? "",
                localizedNames: dto.localizedNames?.additionalProperties,
                icon: dto.icon,
                items: (dto.items ?? []).compactMap(Self.mapContentItem)
            )
        }
        return CursorPage(items: sections, nextCursor: output.value1.pageInfo?.nextCursor)
    }

    func content(type: ListType, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
        // `.all` has no matching `_TypePayload` case, so the lookup falls through to nil (an
        // omitted param) exactly as the explicit switch used to.
        let input = Operations.GetPublicContent.Input(query: .init(
            _type: .init(rawValue: type.rawValue),
            cursor: cursor,
            limit: limit,
            category: filter.categoryId,
            length: filter.length.flatMap { Operations.GetPublicContent.Input.Query.LengthPayload(rawValue: $0.rawValue) },
            date: filter.date.flatMap { Operations.GetPublicContent.Input.Query.DatePayload(rawValue: $0.rawValue) },
            sort: filter.sort.flatMap { Operations.GetPublicContent.Input.Query.SortPayload(rawValue: $0.rawValue) },
            q: query
        ))
        let output = try await client.getPublicContent(input).ok.body.json
        let items = (output.value2.data ?? []).compactMap(Self.mapContentItem)
        return CursorPage(items: items, nextCursor: output.value1.pageInfo?.nextCursor)
    }

    func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] {
        // `.all` (and `nil`) have no matching `_TypePayload` case, so the lookup falls through
        // to nil (an omitted param) exactly as the explicit switch used to.
        let typeParam = type.flatMap { Operations.SearchPublicContent.Input.Query._TypePayload(rawValue: $0.rawValue) }
        let input = Operations.SearchPublicContent.Input(query: .init(q: query, _type: typeParam, limit: limit))
        let dtos = try await client.searchPublicContent(input).ok.body.json
        return dtos.compactMap(Self.mapContentItem)
    }

    /// `dto._type`'s raw value is expected to match a `ContentType` case; if the generator ever
    /// widens the wire enum without a corresponding `ContentType` case, the item is dropped here
    /// (every call site `compactMap`s the result) instead of crashing.
    private static func mapContentItem(_ dto: Components.Schemas.ContentItemDto) -> ContentItem? {
        guard let type = ContentType(rawValue: dto._type.rawValue) else { return nil }
        return ContentItem(
            id: dto.id,
            type: type,
            title: dto.title ?? dto.name ?? "",
            category: dto.category,
            description: dto.description,
            thumbnailURL: dto.thumbnailUrl.flatMap(URL.init(string:)),
            durationSeconds: dto.durationSeconds,
            uploadedDaysAgo: dto.uploadedDaysAgo,
            viewCount: dto.viewCount,
            channelTitle: dto.channelTitle,
            subscribers: dto.subscribers,
            videoCount: dto.videoCount,
            itemCount: dto.itemCount
        )
    }
}
