export function isIconDataUrl(icon: string): boolean {
  return icon.startsWith('data:image/');
}

export function formatNumber(value: number, locale: string): string {
  return new Intl.NumberFormat(locale).format(value);
}

export function formatDate(value: string, locale: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium' }).format(date);
}

export function formatDateTime(value: string, locale: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

/**
 * Strip signed query params from YouTube thumbnail URLs.
 * Signed URLs (sqp=, rs=) expire and cause load failures.
 * Unsigned i.ytimg.com URLs are stable and always resolve.
 */
function sanitizeYtImgUrl(url: string): string {
  if (url.includes('i.ytimg.com/')) {
    return url.split('?')[0];
  }
  return url;
}

/**
 * Extract a video ID from a ytimg thumbnail URL.
 * e.g. "https://i.ytimg.com/vi/5N7cldicyto/hqdefault.jpg" → "5N7cldicyto"
 */
function extractVideoIdFromYtImg(url: string): string | null {
  const match = url.match(/i\.ytimg\.com\/vi\/([^/]+)\//);
  return match ? match[1] : null;
}

/**
 * Get a thumbnail URL for content, falling back to YouTube's default thumbnail
 * when the stored thumbnailUrl is null/empty.
 *
 * For videos: generates YouTube video thumbnail URL from youtubeId
 * For playlists: extracts video ID from ytimg URL for a stable fallback
 * For channels: returns stored URL or null
 */
export function getThumbnailUrl(
  item: { thumbnailUrl?: string | null; youtubeId?: string | null; ytId?: string | null; contentId?: string | null; id?: string | null },
  type?: 'channel' | 'playlist' | 'video'
): string | null {
  if (item.thumbnailUrl) return sanitizeYtImgUrl(item.thumbnailUrl);

  // For videos and playlists without thumbnailUrl, generate from YouTube ID
  const ytId = item.youtubeId || item.ytId || item.contentId || item.id;
  if (ytId && type === 'video') {
    return `https://i.ytimg.com/vi/${ytId}/mqdefault.jpg`;
  }
  if (ytId && type === 'playlist' && ytId.length === 11 && !ytId.startsWith('PL')) {
    return `https://i.ytimg.com/vi/${ytId}/mqdefault.jpg`;
  }

  return null;
}

/**
 * Build an ordered list of fallback thumbnail URLs to try when the primary fails.
 * Returns URLs in priority order; caller should try each in sequence.
 * URLs are sanitized (no signed params) so they can be compared directly to img.src.
 */
export function getThumbnailFallbacks(
  item: { thumbnailUrl?: string | null; youtubeId?: string | null; ytId?: string | null; contentId?: string | null; id?: string | null },
  type?: 'channel' | 'playlist' | 'video'
): string[] {
  const fallbacks: string[] = [];

  if (type === 'video') {
    const ytId = item.youtubeId || item.ytId || item.contentId || item.id;
    if (ytId) {
      fallbacks.push(
        `https://i.ytimg.com/vi/${ytId}/mqdefault.jpg`,
        `https://i.ytimg.com/vi/${ytId}/hqdefault.jpg`,
        `https://i.ytimg.com/vi/${ytId}/default.jpg`
      );
    }
  }

  if (type === 'playlist') {
    // Try extracting video ID from thumbnail URL first
    const sanitized = item.thumbnailUrl ? sanitizeYtImgUrl(item.thumbnailUrl) : null;
    const vidId = sanitized ? extractVideoIdFromYtImg(sanitized) : null;
    // Fall back to the playlist's own YouTube ID (some playlists use their ID as thumbnail key)
    const playlistYtId = vidId || item.youtubeId || item.ytId || item.contentId || item.id;
    if (playlistYtId && playlistYtId.length === 11 && !playlistYtId.startsWith('PL')) {
      fallbacks.push(
        `https://i.ytimg.com/vi/${playlistYtId}/mqdefault.jpg`,
        `https://i.ytimg.com/vi/${playlistYtId}/hqdefault.jpg`,
        `https://i.ytimg.com/vi/${playlistYtId}/default.jpg`
      );
    }
  }

  if (type === 'channel' && item.thumbnailUrl) {
    const url = item.thumbnailUrl;
    if (url.includes('yt3.googleusercontent.com') || url.includes('yt3.ggpht.com')) {
      // Strip any existing size suffix to get the base URL
      const base = url.replace(/=s\d+[^/]*$/, '');
      // Try multiple size variants — the stored URL may have a broken size suffix
      fallbacks.push(
        base + '=s176-c-k-c0x00ffffff-no-rj',
        base + '=s800-c-k-c0x00ffffff-no-rj',
        base + '=s72-c-k-c0x00ffffff-no-rj',
        base  // No suffix — serves default size
      );
    }
  }

  return fallbacks;
}

export function formatDuration(seconds: number, locale: string): string {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '–';
  }

  const rounded = Math.floor(seconds);
  const hours = Math.floor(rounded / 3600);
  const minutes = Math.floor((rounded % 3600) / 60);
  const secs = rounded % 60;

  const hourFormatter = new Intl.NumberFormat(locale, { useGrouping: false, minimumIntegerDigits: 1 });
  const minuteFormatter = new Intl.NumberFormat(locale, {
    useGrouping: false,
    minimumIntegerDigits: hours > 0 ? 2 : 1
  });
  const secondFormatter = new Intl.NumberFormat(locale, { useGrouping: false, minimumIntegerDigits: 2 });

  if (hours > 0) {
    return `${hourFormatter.format(hours)}:${minuteFormatter.format(minutes)}:${secondFormatter.format(secs)}`;
  }

  return `${minuteFormatter.format(minutes)}:${secondFormatter.format(secs)}`;
}
