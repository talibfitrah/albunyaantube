/**
 * The category picker used to assemble its options by paging `/api/admin/content` in a loop that
 * could not terminate on its own: the server's `totalPages` describes its current fetch window,
 * and that window grows by one page per content type each round, so the target outran the loop
 * and it ran to a 20-page safety cap. Offset paging re-read every earlier row each time.
 *
 * The guard is the request count: one call, to the endpoint that answers the picker's question.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getApprovedContent } from '@/services/sortOrder';
import apiClient from '@/services/api/client';

vi.mock('@/services/api/client');

describe('getApprovedContent', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('asks once, on the picker endpoint', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({
      data: {
        items: [
          { type: 'channel', id: 'c1', title: 'A Channel', thumbnailUrl: 'https://img/c1', youtubeId: 'UCc1' }
        ],
        truncated: false
      }
    } as never);

    const result = await getApprovedContent();

    expect(apiClient.get).toHaveBeenCalledTimes(1);
    expect(apiClient.get).toHaveBeenCalledWith('/api/admin/content/approved-picker');
    expect(result.items).toHaveLength(1);
    expect(result.truncated).toBe(false);
  });

  it('keeps both fields the thumbnail falls back through', async () => {
    // getThumbnailUrl() reads thumbnailUrl first and otherwise builds a URL from youtubeId.
    // Dropping either would blank the thumbnails for part of the list while still "looking" fine.
    vi.mocked(apiClient.get).mockResolvedValue({
      data: {
        items: [{ type: 'video', id: 'v1', title: 'A Video', thumbnailUrl: null, youtubeId: 'ytv1' }],
        truncated: false
      }
    } as never);

    const item = (await getApprovedContent()).items[0];

    expect(item).toHaveProperty('thumbnailUrl');
    expect(item.youtubeId).toBe('ytv1');
  });

  it('passes the server truncation flag through so the picker can say the list is partial', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { items: [], truncated: true } } as never);

    expect((await getApprovedContent()).truncated).toBe(true);
  });

  it('survives a response with no body fields', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    const result = await getApprovedContent();

    expect(result.items).toEqual([]);
    expect(result.truncated).toBe(false);
  });
});
