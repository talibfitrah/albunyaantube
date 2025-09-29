# Channel Tabs Data Fetch Sequence

```mermaid
sequenceDiagram
    participant App as Android App
    participant Repo as ChannelRepository
    participant API as Backend API
    participant Cache as Redis
    participant DB as PostgreSQL

    App->>Repo: loadChannelTabs(channelId, locale)
    Repo->>API: GET /channels/{id}?locale
    API->>Cache: fetch channel detail cache key
    Cache-->>API: miss
    API->>DB: SELECT channel + i18n fields
    API->>Cache: store channel detail (TTL 10m)
    API-->>Repo: 200 OK ChannelDetail

    App->>Repo: loadVideosTab(cursor=null)
    Repo->>API: GET /channels/{id}/videos?cursor=
    API->>Cache: fetch list cache key (channelId, locale, cursor)
    Cache-->>API: hit/miss (if miss, fallback to DB query)
    API->>DB: SELECT latest videos ORDER BY publishedAt DESC LIMIT pageSize (if miss)
    API->>Cache: store page (TTL 5m)
    API-->>Repo: 200 OK CursorPage
    Repo-->>App: Render list with Paging 3; handles cursor for next page
```
