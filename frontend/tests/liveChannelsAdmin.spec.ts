import { describe, it, expect, vi } from 'vitest';
import { messages } from '@/locales/messages';
import apiClient from '@/services/api/client';
import { createCategory, updateCategory } from '@/services/categoryService';
import { ApiError, apiErrorCode, apiErrorOffenders, apiErrorToMessage, apiErrorWithOffenders } from '@/services/http';
import { getLiveStatus, liveChips, type LiveEntryStatus } from '@/services/liveChannels';

vi.mock('@/services/api/client');

const CODES = [
  'LIVE_CATEGORY_ADMIN_ONLY', 'LIVE_CATEGORY_TYPE_NOT_ALLOWED', 'LIVE_LINK_NOT_LIVE', 'LIVE_LINK_CHANNEL_UNKNOWN',
  'LIVE_CHECK_UNAVAILABLE', 'LIVE_CATEGORY_FULL', 'LIVE_CATEGORY_SHAPE', 'LIVE_CATEGORY_MEMBERS_INVALID'
];
const KEYS = [
  ...CODES.map(c => `apiErrors.${c}`),
  'categories.dialog.type', 'categories.dialog.typeStandard', 'categories.dialog.typeLive', 'categories.dialog.typeLiveHelp',
  'categoryModal.liveOnlyChannelsAndLive',
  'contentSorting.live.note', 'contentSorting.live.live', 'contentSorting.live.offlineSince', 'contentSorting.live.terminated',
  'contentSorting.live.errorSince', 'contentSorting.live.suspectSince', 'contentSorting.live.notSeen', 'contentSorting.live.noChannel',
  'contentSorting.live.excluded', 'contentSorting.live.blocked', 'contentSorting.live.pending'
];

function lookup(locale: 'en' | 'ar' | 'nl', key: string): unknown {
  return key.split('.').reduce<unknown>((node, part) => (node as Record<string, unknown> | undefined)?.[part], messages[locale]);
}

const t = (key: string, fallback?: string) => {
  const v = lookup('en', key);
  return typeof v === 'string' ? v : fallback ?? key;
};

describe('live channels: strings (spec §8)', () => {
  it.each(['en', 'ar', 'nl'] as const)('every new key exists in %s', locale => {
    for (const key of KEYS) expect(typeof lookup(locale, key), `${locale}:${key}`).toBe('string');
  });

  it('keeps the {time}, {count} and {misses} placeholders in every locale', () => {
    for (const locale of ['en', 'ar', 'nl'] as const) {
      expect(lookup(locale, 'contentSorting.live.suspectSince')).toContain('{time}');
      expect(lookup(locale, 'contentSorting.live.live')).toContain('{count}');
      expect(lookup(locale, 'contentSorting.live.notSeen')).toContain('{misses}');
    }
  });
});

describe('live channels: coded errors from both transports (F20)', () => {
  const body = {
    code: 'LIVE_LINK_NOT_LIVE', message: 'Video yee9gj7t8nQ is not live',
    details: { offenders: [{ ref: 'yee9gj7t8nQ', code: 'LIVE_LINK_NOT_LIVE' }, { ref: 'PLabc', code: 'LIVE_CATEGORY_TYPE_NOT_ALLOWED' }] }
  };
  const axiosError = Object.assign(new Error('Request failed with status code 400'), { response: { status: 400, data: body } });
  const fetchError = new ApiError(body.message, 400, body.code, body.details);

  it('reads the code from an axios body and from an ApiError', () => {
    expect(apiErrorCode(axiosError)).toBe('LIVE_LINK_NOT_LIVE');
    expect(apiErrorCode(fetchError)).toBe('LIVE_LINK_NOT_LIVE');
  });

  it('maps every new code to its localized message', () => {
    for (const code of CODES) {
      const err = Object.assign(new Error('x'), { response: { status: 400, data: { code } } });
      expect(apiErrorToMessage(err, t, 'fallback')).toBe(lookup('en', `apiErrors.${code}`));
    }
  });

  it('lists every offender with its own localized reason', () => {
    expect(apiErrorOffenders(axiosError)).toEqual([{ ref: 'yee9gj7t8nQ', code: 'LIVE_LINK_NOT_LIVE' }, { ref: 'PLabc', code: 'LIVE_CATEGORY_TYPE_NOT_ALLOWED' }]);
    const text = apiErrorWithOffenders(fetchError, t, 'fallback');
    expect(text.split('\n')).toHaveLength(3);
    expect(text).toContain(`PLabc: ${lookup('en', 'apiErrors.LIVE_CATEGORY_TYPE_NOT_ALLOWED')}`);
  });

  it("gives an uncoded error the caller's localized fallback, never transport English", () => {
    const fallback = lookup('en', 'contentSearch.addError');
    const axios500 = Object.assign(new Error('Request failed with status code 500'), { response: { status: 500, data: {} } });
    expect(apiErrorToMessage(axios500, t, 'contentSearch.addError')).toBe(fallback);
    expect(apiErrorToMessage(new ApiError('Request failed with status 500', 500), t, 'contentSearch.addError')).toBe(fallback);
    expect(apiErrorToMessage(new TypeError('Failed to fetch'), t, 'contentSearch.addError')).toBe(fallback);
  });

  it('translates the submission rate limit (429 RATE_LIMIT on single and bulk adds)', () => {
    const err = Object.assign(new Error('Request failed with status code 429'),
      { response: { status: 429, data: { code: 'RATE_LIMIT', message: 'Daily submission limit reached. Try again later.' } } });
    expect(apiErrorToMessage(err, t, 'contentSearch.addError')).toBe(lookup('en', 'apiErrors.RATE_LIMIT'));
    for (const locale of ['ar', 'nl'] as const) {
      expect(typeof lookup(locale, 'apiErrors.RATE_LIMIT'), locale).toBe('string');
      expect(lookup(locale, 'apiErrors.RATE_LIMIT'), locale).not.toBe(lookup('en', 'apiErrors.RATE_LIMIT'));
    }
  });
});

describe('live channels: row chips (spec §8)', () => {
  const time = (iso: string) => `T(${iso})`;
  const channel = (over: Partial<LiveEntryStatus>): LiveEntryStatus =>
    ({ contentType: 'channel', contentId: 'c', status: 'LIVE', lives: [], ...over } as LiveEntryStatus);

  it('counts served lives and flags excluded and blocked ones', () => {
    const chips = liveChips(channel({ lives: [
      { id: 'a', title: '', served: 'SERVED' }, { id: 'b', title: '', served: 'EXCLUDED' }, { id: 'c', title: '', served: 'BLOCKED' }] }), time);
    expect(chips.map(c => c.key)).toEqual(['contentSorting.live.live', 'contentSorting.live.excluded', 'contentSorting.live.blocked']);
    expect(chips[0].params).toEqual({ count: 1 });
  });

  it('names offline, pending, terminated and failing channels', () => {
    expect(liveChips(channel({ status: 'OFFLINE', offlineSince: '2026-10-17T01:20:00Z' }), time)[0])
      .toMatchObject({ key: 'contentSorting.live.offlineSince', params: { time: 'T(2026-10-17T01:20:00Z)' } });
    expect(liveChips(channel({ status: 'UNKNOWN' }), time)[0].key).toBe('contentSorting.live.pending');
    expect(liveChips(channel({ status: 'OFFLINE', reason: 'TERMINATED' }), time)[0].key).toBe('contentSorting.live.terminated');
    expect(liveChips(channel({ errorSince: '2026-10-17T01:20:00Z' }), time)[0].key).toBe('contentSorting.live.errorSince');
  });

  it('names a missing link and a link with no channel', () => {
    const link = (over: Partial<LiveEntryStatus>) => ({ contentType: 'video', contentId: 'v', status: 'LIVE', ...over } as LiveEntryStatus);
    expect(liveChips(link({ status: 'MISSING', misses: 1 }), time)[0]).toMatchObject({ key: 'contentSorting.live.notSeen', params: { misses: 1 } });
    expect(liveChips(link({ status: 'NO_CHANNEL' }), time)[0].key).toBe('contentSorting.live.noChannel');
    expect(liveChips(undefined, time)).toEqual([]);
  });

  // As built (Task 12 review, spec §4.2): the public list drops a BLOCKED link and a non-servable channel whole.
  it('flags a blocked link as not shown', () => {
    const link = { contentType: 'video', contentId: 'v', status: 'BLOCKED' } as LiveEntryStatus;
    expect(liveChips(link, time)).toEqual([{ key: 'contentSorting.live.blocked', tone: 'bad' }]);
  });

  it('flags a non-servable channel as not shown, whatever its poll state', () => {
    const lives = [{ id: 'a', title: '', served: 'BLOCKED' as const }];
    expect(liveChips(channel({ servable: false, lives }), time)).toEqual([{ key: 'contentSorting.live.blocked', tone: 'bad' }]);
    expect(liveChips(channel({ servable: false, status: 'UNKNOWN' }), time)).toEqual([{ key: 'contentSorting.live.blocked', tone: 'bad' }]);
    expect(liveChips(channel({ servable: true, lives: [{ id: 'a', title: '', served: 'SERVED' }] }), time)[0])
      .toMatchObject({ key: 'contentSorting.live.live', params: { count: 1 } });
  });
});

describe('live channels: category type and status on the wire (spec §3, §4.2)', () => {
  it('sends the type on create and update, and reads the status endpoint', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: {} });
    vi.mocked(apiClient.put).mockResolvedValue({ data: {} });
    vi.mocked(apiClient.get).mockResolvedValue({ data: { suspectSince: null, categories: [] } });

    await createCategory({ name: 'Live', type: 'LIVE' });
    expect(vi.mocked(apiClient.post).mock.calls[0][1]).toMatchObject({ type: 'LIVE' });
    await updateCategory('cat-1', { name: 'Live', type: null });
    expect(apiClient.put).toHaveBeenCalledWith('/api/admin/categories/cat-1', { name: 'Live', type: null }, { suppressGlobalErrorToast: true });
    expect(await getLiveStatus()).toEqual({ suspectSince: null, categories: [] });
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/live-channels/status');
  });
});
