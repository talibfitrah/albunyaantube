import FitrahAPI
import Foundation

nonisolated struct LiveCatalogClient: CatalogClient {
    private let client: Client
    private let sleep: @Sendable (Duration) async throws -> Void

    init(client: Client, sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.client = client
        self.sleep = sleep
    }

    func categories() async throws -> [Category] {
        let dtos = try await client.listPublicCategories(Operations.ListPublicCategories.Input()).ok.body.json
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
                items: (dto.items ?? []).map(Self.mapContentItem)
            )
        }
        return CursorPage(items: sections, nextCursor: output.value1.pageInfo?.nextCursor)
    }

    func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
        let input = Operations.GetPublicContent.Input(query: .init(
            _type: type.flatMap { Operations.GetPublicContent.Input.Query._TypePayload(rawValue: $0.rawValue) },
            cursor: cursor,
            limit: limit,
            category: filter.categoryId,
            length: filter.length,
            date: filter.date,
            sort: filter.sort,
            q: query
        ))
        let output = try await retryOnceWhileIndexBuilds { try await client.getPublicContent(input) } retryAfter: {
            if case .serviceUnavailable(let busy) = $0 { busy.headers.retryAfter } else { nil }
        }.ok.body.json
        let items = (output.value2.data ?? []).map(Self.mapContentItem)
        return CursorPage(items: items, nextCursor: output.value1.pageInfo?.nextCursor)
    }

    func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult {
        let typeParam = type.flatMap { Operations.SearchPublicContent.Input.Query._TypePayload(rawValue: $0.rawValue) }
        let input = Operations.SearchPublicContent.Input(query: .init(q: query, _type: typeParam, limit: limit))
        let ok = try await retryOnceWhileIndexBuilds { try await client.searchPublicContent(input) } retryAfter: {
            if case .serviceUnavailable(let busy) = $0 { busy.headers.retryAfter } else { nil }
        }.ok
        return SearchResult(items: try ok.body.json.map(Self.mapContentItem),
                            match: SearchMatch(header: ok.headers.xSearchMatch))
    }

    func liveChannel(id: String) async throws -> LiveChannelLives {
        let dto = try await client.getLiveChannel(Operations.GetLiveChannel.Input(path: .init(channelId: id))).ok.body.json
        return LiveChannelLives(channelId: dto.channelId, status: dto.status.rawValue,
                                lives: dto.lives.map { LiveChannelLives.Live(id: $0.id, title: $0.title) })
    }

    /// A restarted server answers 503 + Retry-After while its search index builds (5 s). Wait that
    /// long -- cancelled with the caller, e.g. by the next keystroke -- and try once more; a longer
    /// wait, any other answer or a second 503 goes to the caller. Android's `retryOnceWhileIndexBuilds`.
    private func retryOnceWhileIndexBuilds<Output>(_ call: () async throws -> Output,
                                                   retryAfter: (Output) -> Int?) async throws -> Output {
        let first = try await call()
        guard let seconds = retryAfter(first), (0...10).contains(seconds) else { return first }
        try await sleep(.seconds(seconds))
        return try await call()
    }

    /// `dto._type`'s three cases (video/channel/playlist) map 1:1 onto `ContentType`. The switch
    /// is exhaustive (no `default`), so a future generator addition to the wire enum is a compile
    /// error here instead of a silently dropped item.
    private static func mapContentItem(_ dto: Components.Schemas.ContentItemDto) -> ContentItem {
        let type: ContentType
        switch dto._type {
        case .video: type = .video
        case .channel: type = .channel
        case .playlist: type = .playlist
        }
        var item = ContentItem(
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
        item.liveChannelId = dto.liveChannelId
        return item
    }
}
