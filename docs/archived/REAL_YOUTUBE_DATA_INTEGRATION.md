# Real YouTube Data Integration Guide

**Date**: November 3, 2025
**Status**: ✅ Complete
**Purpose**: Integration of real YouTube channel, playlist, and video data into Firestore

---

## Overview

Successfully integrated **real YouTube data** from provided JSON file into the Albunyaan Tube backend seeding system. The data includes:
- **5 Channels** (YouTube channels starting with "UC")
- **5 Playlists** (YouTube playlists starting with "PL")
- **218 Videos** (Individual YouTube videos)

Total: **228 real YouTube content items** ready for seeding.

---

## Files Created

### 1. YouTube Data JSON
**File**: `backend/src/main/resources/youtube-data.json`
**Description**: Source data file containing YouTube IDs and titles

**Format**:
```json
[
  { "UCxxx": "Channel Name|Global", ... },  // Channels
  { "PLxxx": "Playlist Title|Global", ... }, // Playlists
  { "videoId": "Video Title|Global", ... }   // Videos
]
```

**Content**:
- 5 channels (Zad Group, Learn with Zakaria, Rachids Welt, etc.)
- 5 playlists (Arabic Alphabet, Arabic for Kids, etc.)
- 218 videos (Islamic education, Quran recitation, nasheeds, kids content)

### 2. YouTube Data Parser
**File**: `backend/src/main/java/com/albunyaan/tube/util/YouTubeDataParser.java`
**Description**: Utility to parse JSON and identify content types

**Features**:
- Identifies channels (IDs starting with "UC")
- Identifies playlists (IDs starting with "PL", "UU", "OL")
- Identifies videos (11-character IDs)
- Extracts titles and tags
- Groups content by tag

**API**:
```java
ParsedYouTubeData data = YouTubeDataParser.parseFromResource();
// Returns: channels, playlists, videos

Set<String> tags = YouTubeDataParser.extractTags(data);
Map<String, List<YouTubeChannel>> channelsByTag = YouTubeDataParser.groupChannelsByTag(data);
```

### 3. Real YouTube Data Seeder
**File**: `backend/src/main/java/com/albunyaan/tube/util/RealYouTubeDataSeeder.java`
**Description**: CommandLineRunner that seeds Firestore with real YouTube data

**Profile**: `real-seed`
**Run command**:
```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=real-seed'
```

**Features**:
- Creates 8 base categories if they don't exist
- Seeds channels with proper metadata
- Seeds playlists with thumbnails
- Seeds videos with:
  - Titles from JSON
  - Auto-generated descriptions
  - YouTube thumbnail URLs
  - Random durations (2-32 minutes)
  - Random view counts (1K-100K)
  - Distributed upload dates (last 365 days)
  - APPROVED status
- Prevents duplicates (checks existing IDs)
- Assigns all content to "kids" category by default (since all tagged as "Global")

---

## Data Mapping

### YouTube ID Detection

| Type | ID Pattern | Example | Count |
|------|------------|---------|-------|
| Channel | Starts with `UC` | `UCw0OFJrMMH6N5aTyeOTTWZQ` | 5 |
| Playlist | Starts with `PL`, `UU`, `OL` | `PLEaGEZnOHpUP4SKUKrg3Udghc5zJ_tH0g` | 5 |
| Video | 11-12 characters | `EnfgPg0Ey3I` | 218 |

### Category Assignment

All content tagged as "|Global" is mapped to the "kids" category. You can customize category mapping in `RealYouTubeDataSeeder.java`:

```java
private static final Map<String, String> TAG_TO_CATEGORY = Map.ofEntries(
        Map.entry("Global", "kids"),  // Change this mapping
        Map.entry("Quran", "quran"),
        Map.entry("Nasheed", "nasheed"),
        // Add more mappings as needed
);
```

### Generated Metadata

Since the JSON only provides YouTube IDs and titles, the seeder generates additional metadata:

#### Channels
- `youtubeId`: From JSON
- `name`: From JSON title
- `description`: "YouTube channel: {name}"
- `thumbnailUrl`: `https://yt3.ggpht.com/ytc/{youtubeId}`
- `categoryIds`: `["kids"]` (from tag mapping)
- `status`: `"APPROVED"`

#### Playlists
- `youtubeId`: From JSON
- `title`: From JSON title
- `description`: "YouTube playlist: {title}"
- `thumbnailUrl`: `https://i.ytimg.com/vi/{playlistId}/mqdefault.jpg`
- `itemCount`: `10` (placeholder)
- `categoryIds`: `["kids"]`
- `status`: `"APPROVED"`

#### Videos
- `youtubeId`: From JSON
- `title`: From JSON title
- `description`: Auto-generated based on title keywords
- `thumbnailUrl`: `https://i.ytimg.com/vi/{videoId}/mqdefault.jpg`
- `durationSeconds`: Random (120-1920 seconds = 2-32 minutes)
- `viewCount`: Random (1000-100000)
- `uploadedAt`: Distributed across last 365 days
- `categoryIds`: `["kids"]`
- `status`: `"APPROVED"`

### Description Generation Logic

The seeder intelligently generates descriptions based on title content:

| Title Contains | Generated Description |
|----------------|----------------------|
| "قرآن", "Quran", "سورة" | "Quranic recitation and learning content" |
| "نشيد", "Nasheed" | "Islamic nasheed (vocal-only)" |
| "تعليم", "Learn", "Arabic" | "Educational content for learning" |
| "طفال", "Kids", "Children" | "Islamic educational content for children" |
| *Default* | "Islamic educational content" |

---

## Usage

### 1. Run the Seeder

```bash
# Navigate to backend
cd /home/farouq/Development/albunyaantube/backend

# Run with real-seed profile
./gradlew bootRun --args='--spring.profiles.active=real-seed'
```

**Expected Output**:
```
🚀 Starting Real YouTube Data Seeding...
Creating base categories...
✅ Created 8 base categories
✅ Seeded 5 channels
Seeded 50 videos so far...
Seeded 100 videos so far...
Seeded 150 videos so far...
Seeded 200 videos so far...
✅ Seeded 5 playlists
✅ Seeded 218 videos
🎉 Real YouTube Data Seeding Complete!
📊 Total: 5 channels, 5 playlists, 218 videos
```

### 2. Verify Data in Firestore

After seeding, you can verify the data:

**Using Firebase Emulator UI** (if running locally):
```bash
# Open Firebase Emulator UI
open http://localhost:4000

# Check Firestore collections:
# - channels: Should have 5 items
# - playlists: Should have 5 items
# - videos: Should have 218 items
# - categories: Should have 8 items
```

**Using API**:
```bash
# Get all channels
curl http://localhost:8080/api/v1/content/channels | jq '. | length'
# Should return: 5

# Get all playlists
curl http://localhost:8080/api/v1/content/playlists | jq '. | length'
# Should return: 5

# Get all videos
curl http://localhost:8080/api/v1/content/videos | jq '. | length'
# Should return: 218

# Get categories
curl http://localhost:8080/api/v1/categories | jq '. | length'
# Should return: 8 (or more if you already had categories)
```

### 3. Test in Android App

After seeding, rebuild and test the Android app:

```bash
# Build Android APK
cd /home/farouq/Development/albunyaantube/android
./gradlew assembleDebug

# Install on device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch and verify:
# - Videos tab should show 218 videos
# - Channels tab should show 5 channels
# - Playlists tab should show 5 playlists
# - All should have proper thumbnails and titles
```

---

## Content Preview

### Channels (5 total)

1. **مجموعة زاد** (Zad Group)
   - YouTube ID: `UCw0OFJrMMH6N5aTyeOTTWZQ`
   - Category: Kids Corner

2. **قناة زاد العلمية** (Zad Scientific Channel)
   - YouTube ID: `UCOll3M-P7oKs5cSrQ9ytt6g`
   - Category: Kids Corner

3. **برنامج أكاديمية زاد - Zad academy**
   - YouTube ID: `UCBoe29aQT-zMECFyyyO7H4Q`
   - Category: Kids Corner

4. **Learn with Zakaria - تعلم مع زكريا**
   - YouTube ID: `UCtlcIZVBdFPSAtCoNZsTusg`
   - Category: Kids Corner

5. **Rachids Welt**
   - YouTube ID: `UCq_38-upzmQVmF7tmL-n3SA`
   - Category: Kids Corner

### Playlists (5 total)

1. **Arabic Alphabet for Children - حروف الهجاء للأطفال**
   - Playlist ID: `PLEaGEZnOHpUP4SKUKrg3Udghc5zJ_tH0g`

2. **Learn Arabic for Kids - تعليم اللغة العربية للأطفال**
   - Playlist ID: `PLEaGEZnOHpUPBcDnCCXkmgsgRDICnhYwT`

3. **learn arabic for kids - Apprendre larabe pour enfants**
   - Playlist ID: `PLUitXL66pnO-yT8kCjZX7fIcx8ksPkJ47`

4. **اناشيد الروضة - تعليم الاطفال - بدون موسيقى - بدون ايقاع**
   - Playlist ID: `PLAdw4_nim6oI-AJf49TfdBqaNENUy6WHK`

5. **Learn Arabic with Rachid**
   - Playlist ID: `PLN_d6itAXnCIm9FYgbypxIzaLpb2ePvGx`

### Videos (218 total)

Sample videos:
- نشيد طلب العلم (Nasheed about seeking knowledge)
- مشاري راشد العفاسي أذكار الصباح (Mishari Alafasy morning dhikr)
- Best Arabic Alphabet Song
- الفيلم الكرتوني الشيق : القائد طارق بن زياد (Animated film: Tariq ibn Ziyad)
- تعليم الحروف الهجائية للأطفال (Teaching Arabic letters to children)
- سورة البقرة مشاري راشد العفاسي (Surah Al-Baqarah recitation)
- ...and 212 more educational and Islamic content videos

---

## Customization

### Adding More Content

To add more YouTube content:

1. **Edit the JSON file**: `backend/src/main/resources/youtube-data.json`
   ```json
   {
     "UCnewChannelId": "New Channel Name|Category",
     "PLnewPlaylistId": "New Playlist Title|Category",
     "newVideoId11": "New Video Title|Category"
   }
   ```

2. **Update category mappings** in `RealYouTubeDataSeeder.java` if needed
3. **Re-run the seeder**: `./gradlew bootRun --args='--spring.profiles.active=real-seed'`

### Changing Category Assignments

Edit the `TAG_TO_CATEGORY` map in `RealYouTubeDataSeeder.java`:

```java
private static final Map<String, String> TAG_TO_CATEGORY = Map.ofEntries(
        Map.entry("Global", "kids"),        // Change to different category
        Map.entry("Quran", "quran"),        // Add Quran tag mapping
        Map.entry("Nasheed", "nasheed"),    // Add Nasheed tag mapping
        Map.entry("Education", "education") // Add Education tag mapping
);
```

Then update your JSON to use these tags:
```json
{
  "UCw0OFJrMMH6N5aTyeOTTWZQ": "مجموعة زاد|Quran",
  "UCOll3M-P7oKs5cSrQ9ytt6g": "قناة زاد العلمية|Education"
}
```

### Fetching Real Metadata from YouTube

The current seeder uses placeholder metadata. To fetch real data from YouTube API:

1. **Add YouTube Data API integration** to `YouTubeService`
2. **Modify seeder** to call `YouTubeService.fetchVideoMetadata(videoIds)`
3. **Update models** with real data:
   - Channel subscriber counts
   - Playlist item counts
   - Video durations, view counts, upload dates
   - Real thumbnails

Example:
```java
// In seedVideos()
for (YouTubeDataParser.YouTubeVideo ytVideo : videos) {
    // Fetch real metadata from YouTube
    VideoMetadata metadata = youtubeService.getVideoMetadata(ytVideo.videoId);

    video.setTitle(metadata.title);
    video.setDescription(metadata.description);
    video.setDurationSeconds(metadata.durationSeconds);
    video.setViewCount(metadata.viewCount);
    video.setUploadedAt(metadata.uploadDate);
    // ... etc
}
```

---

## Comparison: Stub Data vs Real Data

| Aspect | Old Stub Data | New Real Data |
|--------|---------------|---------------|
| **Channels** | 20 fake channels | **5 real YouTube channels** |
| **Playlists** | 16 fake playlists | **5 real YouTube playlists** |
| **Videos** | 76 fake videos | **218 real YouTube videos** |
| **YouTube IDs** | Generated (invalid) | **Real YouTube IDs** |
| **Titles** | Generic placeholders | **Real Arabic/English titles** |
| **Thumbnails** | Placeholder images | **YouTube thumbnail URLs** |
| **Metadata** | Hardcoded | **Generated/randomized** |
| **Source** | Code constants | **External JSON file** |

---

## Next Steps

1. **✅ Backend Seeding**: Complete
   - Created YouTube data parser
   - Created real data seeder
   - Built successfully

2. **⏳ Run Seeding** (Next step):
   ```bash
   cd backend
   ./gradlew bootRun --args='--spring.profiles.active=real-seed'
   ```

3. **⏳ Fetch Real Metadata** (Optional enhancement):
   - Integrate YouTube Data API
   - Fetch real thumbnails, durations, view counts
   - Update seeder to use real data

4. **⏳ Test in Android App**:
   - Build APK
   - Install on device
   - Verify real YouTube content displays
   - Test video playback with NewPipe

5. **⏳ Categorize Content** (Optional):
   - Review video titles
   - Create better category mappings
   - Update JSON with appropriate tags
   - Re-run seeder

---

## Troubleshooting

### Seeder doesn't run
**Issue**: Profile not activated
**Solution**: Make sure to use `--spring.profiles.active=real-seed`

### Duplicate key errors
**Issue**: Content already exists in Firestore
**Solution**: Seeder checks for existing IDs and skips them automatically

### Missing categories
**Issue**: Videos/playlists/channels have no category
**Solution**: Seeder creates 8 base categories automatically

### Wrong category assignments
**Issue**: All content in "kids" category
**Solution**: Update `TAG_TO_CATEGORY` mapping and JSON tags

### No thumbnails showing
**Issue**: YouTube thumbnail URLs may be incorrect
**Solution**: Use YouTube Data API to fetch real thumbnail URLs

---

## Summary

Successfully created a complete pipeline for importing real YouTube data:

1. ✅ **JSON Data Source**: 228 real YouTube items
2. ✅ **Parser**: Identifies channels, playlists, videos
3. ✅ **Seeder**: Populates Firestore with real data
4. ✅ **Build**: Compiles successfully
5. ⏳ **Deployment**: Ready to run

**Impact**: The Android app will now display **real Islamic educational content** instead of placeholder data, making it a functional app ready for testing and deployment!

---

**Last Updated**: November 3, 2025
**Status**: Ready for seeding
