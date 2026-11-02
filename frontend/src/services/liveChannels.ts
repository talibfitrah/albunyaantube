import apiClient from './api/client';

/** Spec §4.2: GET /api/admin/live-channels/status. */
export type LiveServed = 'SERVED' | 'EXCLUDED' | 'BLOCKED' | 'ENDED';

export interface LiveEntryStatus {
  contentType: 'channel' | 'video';
  contentId: string;
  channelId?: string | null;
  youtubeId?: string;
  name?: string;
  /** Channels: the poll state. Links: LIVE, MISSING, ENDED, NO_CHANNEL or BLOCKED (precedence NO_CHANNEL > BLOCKED > ENDED > MISSING). */
  status: 'LIVE' | 'OFFLINE' | 'UNKNOWN' | 'MISSING' | 'ENDED' | 'NO_CHANNEL' | 'BLOCKED';
  /** Channels only: false when the channel's own doc fails the public filters, so the list drops it whole. */
  servable?: boolean;
  reason?: string | null;
  lives?: { id: string; title: string; served: LiveServed }[];
  checkedAt?: string | null;
  offlineSince?: string | null;
  errorSince?: string | null;
  lastError?: string | null;
  misses?: number;
  missingSince?: string | null;
}

export interface LiveStatus {
  suspectSince: string | null;
  categories: { id: string; entries: LiveEntryStatus[] }[];
}

export interface LiveChip {
  key: string;
  params?: Record<string, unknown>;
  tone: 'ok' | 'warn' | 'bad';
}

export async function getLiveStatus(): Promise<LiveStatus> {
  const response = await apiClient.get<LiveStatus>('/api/admin/live-channels/status', { suppressGlobalErrorToast: true });
  return response.data;
}

/** The Content Sorting row chips for one entry (spec §8); `formatTime` is the view's locale formatter. */
export function liveChips(entry: LiveEntryStatus | undefined, formatTime: (iso: string) => string): LiveChip[] {
  if (!entry) return [];
  if (entry.contentType === 'video') {
    if (entry.status === 'NO_CHANNEL') return [{ key: 'contentSorting.live.noChannel', tone: 'bad' }];
    if (entry.status === 'BLOCKED') return [{ key: 'contentSorting.live.blocked', tone: 'bad' }];
    if (entry.status === 'ENDED') return [{ key: 'contentSorting.live.ended', tone: 'bad' }];
    if (entry.status === 'MISSING') return [{ key: 'contentSorting.live.notSeen', params: { misses: entry.misses ?? 1 }, tone: 'bad' }];
    return [{ key: 'contentSorting.live.live', params: { count: 1 }, tone: 'ok' }];
  }
  if (entry.reason === 'TERMINATED') return [{ key: 'contentSorting.live.terminated', tone: 'bad' }];
  // The poller skips a non-servable channel, so its poll state is stale or absent: say why it isn't shown.
  if (entry.servable === false) return [{ key: 'contentSorting.live.blocked', tone: 'bad' }];
  if (entry.errorSince) return [{ key: 'contentSorting.live.errorSince', params: { time: formatTime(entry.errorSince) }, tone: 'bad' }];
  if (entry.status === 'UNKNOWN') return [{ key: 'contentSorting.live.pending', tone: 'warn' }];
  if (entry.status === 'OFFLINE') {
    return [entry.offlineSince
      ? { key: 'contentSorting.live.offlineSince', params: { time: formatTime(entry.offlineSince) }, tone: 'bad' }
      : { key: 'contentSorting.live.offline', tone: 'bad' }];
  }
  const lives = entry.lives ?? [];
  const chips: LiveChip[] = [{ key: 'contentSorting.live.live', params: { count: lives.filter(l => l.served === 'SERVED').length }, tone: 'ok' }];
  if (lives.some(l => l.served === 'EXCLUDED')) chips.push({ key: 'contentSorting.live.excluded', tone: 'warn' });
  if (lives.some(l => l.served === 'BLOCKED')) chips.push({ key: 'contentSorting.live.blocked', tone: 'warn' });
  return chips;
}

/** The entry's own YouTube page, so the admin can verify its state; null unless the id is well formed. */
export function liveYoutubeUrl(entry: LiveEntryStatus | undefined): string | null {
  if (entry?.contentType === 'channel' && /^UC[A-Za-z0-9_-]{22}$/.test(entry.channelId ?? '')) {
    return `https://www.youtube.com/channel/${entry.channelId}/live`;
  }
  if (entry?.contentType === 'video' && /^[A-Za-z0-9_-]{11}$/.test(entry.youtubeId ?? '')) {
    return `https://www.youtube.com/watch?v=${entry.youtubeId}`;
  }
  return null;
}
