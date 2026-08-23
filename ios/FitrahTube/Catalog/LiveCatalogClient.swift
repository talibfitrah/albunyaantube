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
                id: dto.id ?? "",
                name: dto.name ?? "",
                localizedNames: dto.localizedNames?.additionalProperties,
                icon: dto.icon,
                items: (dto.items ?? []).compactMap(Self.mapContentItem)
            )
        }
        return CursorPage(items: sections, nextCursor: output.value1.pageInfo?.nextCursor)
    }

    func content(type: ListType, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
        let typeParam: Operations.GetPublicContent.Input.Query._TypePayload?
        switch type {
        case .videos: typeParam = .videos
        case .channels: typeParam = .channels
        case .playlists: typeParam = .playlists
        case .all: typeParam = nil // ANY/ALL maps to an omitted param, not an explicit value.
        }
        let input = Operations.GetPublicContent.Input(query: .init(
            _type: typeParam,
            cursor: cursor,
            limit: limit,
            category: filter.categoryId,
            length: filter.length.flatMap(Operations.GetPublicContent.Input.Query.LengthPayload.init(rawValue:)),
            date: filter.date.flatMap(Operations.GetPublicContent.Input.Query.DatePayload.init(rawValue:)),
            sort: filter.sort.flatMap(Operations.GetPublicContent.Input.Query.SortPayload.init(rawValue:)),
            q: query
        ))
        let output = try await client.getPublicContent(input).ok.body.json
        let items = (output.value2.data ?? []).compactMap(Self.mapContentItem)
        return CursorPage(items: items, nextCursor: output.value1.pageInfo?.nextCursor)
    }

    func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] {
        let typeParam: Operations.SearchPublicContent.Input.Query._TypePayload?
        switch type {
        case .videos: typeParam = .videos
        case .channels: typeParam = .channels
        case .playlists: typeParam = .playlists
        case .all, .none: typeParam = nil
        }
        let input = Operations.SearchPublicContent.Input(query: .init(q: query, _type: typeParam, limit: limit))
        let dtos = try await client.searchPublicContent(input).ok.body.json
        return dtos.compactMap(Self.mapContentItem)
    }

    /// `ContentItemDto.type` decodes through a closed 3-case enum (`_TypePayload`), so an
    /// unrecognized wire value fails JSON decoding before it ever reaches this mapper — the
    /// whole response throws, not a single item. This still returns an optional and every call
    /// site `compactMap`s it, so if the generator/spec ever widens `type` into an open enum,
    /// unknown items are dropped silently (no log) instead of crashing.
    private static func mapContentItem(_ dto: Components.Schemas.ContentItemDto) -> ContentItem? {
        let type: ContentType
        switch dto._type {
        case .video: type = .video
        case .channel: type = .channel
        case .playlist: type = .playlist
        }
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
