# NewPipeExtractor Library Reference

> **Version**: 0.24.8
> **Purpose**: Java framework for scraping video platform websites as a structured API
> **No API Keys Required**: Extracts data directly from public web pages
> **JavaDoc**: https://teamnewpipe.github.io/NewPipeExtractor/javadoc/
> **Documentation**: https://teamnewpipe.github.io/documentation/

---

## Quick Start

### Initialization (Required Once)

```java
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.Localization;

// Initialize with a Downloader implementation
NewPipe.init(new MyDownloader());

// Or with localization
NewPipe.init(new MyDownloader(), Localization.DEFAULT);
```

### Basic Usage Pattern

```java
// Get YouTube service
StreamingService youtube = ServiceList.YouTube;

// Extract video info
StreamInfo info = StreamInfo.getInfo("https://www.youtube.com/watch?v=VIDEO_ID");
String title = info.getName();
long views = info.getViewCount();
List<VideoStream> streams = info.getVideoStreams();

// Extract channel info
ChannelInfo channel = ChannelInfo.getInfo("https://www.youtube.com/channel/CHANNEL_ID");
String channelName = channel.getName();
long subscribers = channel.getSubscriberCount();

// Extract playlist info
PlaylistInfo playlist = PlaylistInfo.getInfo("https://www.youtube.com/playlist?list=PLAYLIST_ID");
long videoCount = playlist.getStreamCount();
```

---

## Architecture

### Extractor/Collector Pattern

- **Extractors**: Retrieve raw data from websites, implement methods for each data field
- **Collectors**: Assemble extracted fragments into structured `Info` objects
- **Info Classes**: Final result objects (`StreamInfo`, `ChannelInfo`, `PlaylistInfo`)

### Flow

```
URL → LinkHandler → Extractor → fetchPage() → Info.getInfo() → Info object
```

---

## Available Services

| Service | Class | Access |
|---------|-------|--------|
| YouTube | `YoutubeService` | `ServiceList.YouTube` |
| SoundCloud | `SoundcloudService` | `ServiceList.SoundCloud` |
| PeerTube | `PeertubeService` | `ServiceList.PeerTube` |
| MediaCCC | `MediaCCCService` | `ServiceList.MediaCCC` |
| Bandcamp | `BandcampService` | `ServiceList.Bandcamp` |

```java
// Get service by URL (auto-detect)
StreamingService service = NewPipe.getServiceByUrl(url);

// Get all services
List<StreamingService> services = NewPipe.getServices();
```

---

## Core Classes

### NewPipe (Entry Point)

| Method | Description |
|--------|-------------|
| `init(Downloader d)` | Initialize with downloader |
| `init(Downloader d, Localization l)` | Initialize with localization |
| `init(Downloader d, Localization l, ContentCountry c)` | Full initialization |
| `getService(int serviceId)` | Get service by ID |
| `getService(String name)` | Get service by name |
| `getServiceByUrl(String url)` | Auto-detect service from URL |
| `getServices()` | Get all available services |
| `getDownloader()` | Get configured downloader |

### StreamInfo (Video/Audio Content)

**Static Methods:**
```java
StreamInfo.getInfo(String url)
StreamInfo.getInfo(StreamingService service, String url)
```

**Key Getters:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getName()` | `String` | Video title |
| `getDescription()` | `Description` | Full description with HTML |
| `getThumbnails()` | `List<Image>` | Preview images |
| `getDuration()` | `long` | Duration in seconds (0 for live) |
| `getViewCount()` | `long` | View count |
| `getLikeCount()` | `long` | Like count (-1 if unavailable) |
| `getDislikeCount()` | `long` | Dislike count (-1 if unavailable) |
| `getUploaderName()` | `String` | Channel/uploader name |
| `getUploaderUrl()` | `String` | Channel URL |
| `getUploaderAvatars()` | `List<Image>` | Channel avatars |
| `getUploaderSubscriberCount()` | `long` | Subscriber count |
| `getVideoStreams()` | `List<VideoStream>` | Video+audio streams |
| `getVideoOnlyStreams()` | `List<VideoStream>` | Video-only streams |
| `getAudioStreams()` | `List<AudioStream>` | Audio-only streams |
| `getSubtitles()` | `List<SubtitlesStream>` | Caption tracks |
| `getDashMpdUrl()` | `String` | DASH manifest URL |
| `getHlsUrl()` | `String` | HLS stream URL |
| `getStreamType()` | `StreamType` | VIDEO_STREAM, AUDIO_STREAM, LIVE_STREAM, etc. |
| `getAgeLimit()` | `int` | Age restriction (0 = none) |
| `getCategory()` | `String` | Content category |
| `getTags()` | `List<String>` | Video tags |
| `getStreamSegments()` | `List<StreamSegment>` | Video chapters |
| `getRelatedItems()` | `List<InfoItem>` | Related videos |
| `getPrivacy()` | `Privacy` | PUBLIC, UNLISTED, PRIVATE |
| `isShortFormContent()` | `boolean` | YouTube Shorts, TikTok, etc. |

### ChannelInfo

**Static Methods:**
```java
ChannelInfo.getInfo(String url)
ChannelInfo.getInfo(StreamingService service, String url)
```

**Key Getters:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getName()` | `String` | Channel name |
| `getId()` | `String` | Channel ID |
| `getUrl()` | `String` | Channel URL |
| `getDescription()` | `String` | Channel description |
| `getAvatars()` | `List<Image>` | Profile pictures |
| `getBanners()` | `List<Image>` | Banner images |
| `getSubscriberCount()` | `long` | Subscriber count |
| `getFeedUrl()` | `String` | RSS feed URL |
| `getTabs()` | `List<ListLinkHandler>` | Channel tabs (videos, playlists, etc.) |
| `getTags()` | `List<String>` | Channel tags |
| `isVerified()` | `boolean` | Verification badge |
| `getParentChannelName()` | `String` | Parent channel (for sub-channels) |
| `getParentChannelUrl()` | `String` | Parent channel URL |

**Constant:**
- `UNKNOWN_SUBSCRIBER_COUNT = -1` - Use to check if subscriber count is available

### PlaylistInfo

**Static Methods:**
```java
PlaylistInfo.getInfo(String url)
PlaylistInfo.getInfo(StreamingService service, String url)
PlaylistInfo.getMoreItems(StreamingService service, String url, Page page)
```

**Key Getters:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getName()` | `String` | Playlist title |
| `getId()` | `String` | Playlist ID |
| `getUrl()` | `String` | Playlist URL |
| `getDescription()` | `Description` | Playlist description |
| `getThumbnails()` | `List<Image>` | Playlist thumbnails |
| `getBanners()` | `List<Image>` | Banner images |
| `getUploaderName()` | `String` | Creator name |
| `getUploaderUrl()` | `String` | Creator channel URL |
| `getUploaderAvatars()` | `List<Image>` | Creator avatars |
| `getStreamCount()` | `long` | Number of videos |
| `getPlaylistType()` | `PlaylistType` | NORMAL, MIX_STREAM, MIX_CHANNEL, etc. |
| `getRelatedItems()` | `List<StreamInfoItem>` | Videos in playlist |
| `getNextPage()` | `Page` | Pagination for more items |

---

## Extractors (Low-Level)

Use extractors when you need more control than `Info.getInfo()`:

### StreamExtractor

```java
StreamExtractor extractor = youtube.getStreamExtractor(url);
extractor.fetchPage();  // Must call before accessing data

String title = extractor.getName();
String uploaderName = extractor.getUploaderName();
List<VideoStream> videos = extractor.getVideoStreams();
```

**Key Methods:**

| Method | Return Type | Description |
|--------|-------------|-------------|
| `fetchPage()` | `void` | Load page data (required first) |
| `getId()` | `String` | Content ID |
| `getName()` | `String` | Title |
| `getUrl()` | `String` | Canonical URL |
| `getThumbnails()` | `List<Image>` | Thumbnails |
| `getDescription()` | `Description` | Full description |
| `getUploaderName()` | `String` | Channel name |
| `getUploaderUrl()` | `String` | Channel URL |
| `getLength()` | `long` | Duration in seconds |
| `getViewCount()` | `long` | Views |
| `getLikeCount()` | `long` | Likes |
| `getAudioStreams()` | `List<AudioStream>` | Audio streams |
| `getVideoStreams()` | `List<VideoStream>` | Video streams |
| `getVideoOnlyStreams()` | `List<VideoStream>` | Video-only streams |
| `getStreamType()` | `StreamType` | Content type |

### ChannelExtractor

```java
ChannelExtractor extractor = youtube.getChannelExtractor(url);
extractor.fetchPage();

String name = extractor.getName();
long subs = extractor.getSubscriberCount();
```

### PlaylistExtractor

```java
PlaylistExtractor extractor = youtube.getPlaylistExtractor(url);
extractor.fetchPage();

String title = extractor.getName();
long count = extractor.getStreamCount();
InfoItemsPage<StreamInfoItem> page = extractor.getInitialPage();
```

### SearchExtractor

```java
SearchExtractor extractor = youtube.getSearchExtractor("search query");
extractor.fetchPage();

InfoItemsPage<InfoItem> results = extractor.getInitialPage();
for (InfoItem item : results.getItems()) {
    // Process results
}

// Get next page
if (results.hasNextPage()) {
    InfoItemsPage<InfoItem> nextPage = extractor.getPage(results.getNextPage());
}
```

---

## LinkHandler

LinkHandlers normalize URLs and extract IDs:

```java
// Get handler from service
LinkHandlerFactory factory = youtube.getStreamLHFactory();

// Extract ID from any valid URL format
String id = factory.getId("https://youtu.be/VIDEO_ID");
String id = factory.getId("https://www.youtube.com/watch?v=VIDEO_ID");

// Generate canonical URL from ID
String url = factory.getUrl("VIDEO_ID");

// Check if URL is valid for this handler
boolean valid = factory.acceptUrl(someUrl);
```

---

## Pagination

For lists (search results, channel videos, playlist items):

```java
// Get initial page
InfoItemsPage<StreamInfoItem> page = extractor.getInitialPage();
List<StreamInfoItem> items = page.getItems();

// Check for more pages
while (page.hasNextPage()) {
    page = extractor.getPage(page.getNextPage());
    items.addAll(page.getItems());
}
```

---

## Exception Handling

All exceptions extend `ExtractionException`:

| Exception | Cause |
|-----------|-------|
| `ExtractionException` | Base extraction error |
| `ParsingException` | Failed to parse response |
| `ContentNotAvailableException` | Content deleted/removed |
| `ContentNotSupportedException` | Unsupported content type |
| `PrivateContentException` | Private video/playlist |
| `AgeRestrictedContentException` | Age-gated content |
| `GeographicRestrictionException` | Region-blocked content |
| `PaidContentException` | Premium/paid content |
| `AccountTerminatedException` | Channel terminated |
| `ReCaptchaException` | CAPTCHA required |
| `YoutubeMusicPremiumContentException` | YouTube Music premium |
| `SoundCloudGoPlusContentException` | SoundCloud Go+ premium |

**Recommended Pattern:**

```java
try {
    StreamInfo info = StreamInfo.getInfo(url);
} catch (ContentNotAvailableException e) {
    // Video deleted or unavailable
} catch (PrivateContentException e) {
    // Video is private
} catch (AgeRestrictedContentException e) {
    // Age-restricted content
} catch (GeographicRestrictionException e) {
    // Region blocked
} catch (ExtractionException e) {
    // General extraction error
} catch (IOException e) {
    // Network error
}
```

---

## Stream Types

```java
public enum StreamType {
    VIDEO_STREAM,      // Regular video
    AUDIO_STREAM,      // Audio only (podcasts, music)
    LIVE_STREAM,       // Live broadcast
    AUDIO_LIVE_STREAM, // Audio-only live
    POST,              // Community post
    NONE               // Unknown
}
```

---

## Image Handling

Images are returned as `List<Image>` with multiple resolutions:

```java
List<Image> thumbnails = info.getThumbnails();
for (Image img : thumbnails) {
    String url = img.getUrl();
    int width = img.getWidth();
    int height = img.getHeight();
    Image.ResolutionLevel level = img.getResolutionLevel(); // LOW, MEDIUM, HIGH, UNKNOWN
}
```

---

## Best Practices

1. **Always call `fetchPage()`** before accessing extractor data
2. **Cache results** - extraction makes network calls
3. **Handle all exception types** - different errors need different handling
4. **Use `Info.getInfo()`** for simple cases, extractors for advanced control
5. **Check for `-1` values** - indicates unavailable data (likes, subscribers, etc.)
6. **Use pagination** - don't assume all items are in the first page
7. **Initialize once** - call `NewPipe.init()` once at app startup

---

## Common Patterns in This Project

### YouTubeService.java Usage

```java
// Validate video exists
try {
    StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube,
        "https://www.youtube.com/watch?v=" + youtubeId);
    return true; // Video exists
} catch (ContentNotAvailableException e) {
    return false; // Video unavailable
}

// Batch validate
Map<String, StreamInfo> results = new HashMap<>();
for (String id : youtubeIds) {
    try {
        results.put(id, StreamInfo.getInfo(...));
    } catch (ExtractionException e) {
        // Skip unavailable
    }
}
```

### Search Pattern

```java
SearchExtractor search = ServiceList.YouTube.getSearchExtractor(query);
search.fetchPage();
InfoItemsPage<InfoItem> page = search.getInitialPage();

for (InfoItem item : page.getItems()) {
    if (item instanceof StreamInfoItem video) {
        // Process video result
    } else if (item instanceof ChannelInfoItem channel) {
        // Process channel result
    } else if (item instanceof PlaylistInfoItem playlist) {
        // Process playlist result
    }
}
```
