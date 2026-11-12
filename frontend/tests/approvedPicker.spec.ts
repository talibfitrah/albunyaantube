/**
 * The category picker's options: every approved item, the same set the Library counts as
 * approved. The server pages them by cursor with no ceiling; the client follows the cursor to the end.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getApprovedContent } from '@/services/sortOrder';
import apiClient from '@/services/api/client';

vi.mock('@/services/api/client');

const item = (id: string) =>
  ({ type: 'channel', id, title: `Channel ${id}`, thumbnailUrl: `https://img/${id}`, youtubeId: `UC${id}`, visibility: 'PUBLIC' });

describe('getApprovedContent', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('follows the cursor until the server says nothing is left', async () => {
    vi.mocked(apiClient.get)
      .mockResolvedValueOnce({ data: { items: [item('c1'), item('c2')], nextCursor: 'k1' } } as never)
      .mockResolvedValueOnce({ data: { items: [item('c3')], nextCursor: null } } as never);

    const items = await getApprovedContent();

    expect(items.map(i => i.id)).toEqual(['c1', 'c2', 'c3']);
    expect(apiClient.get).toHaveBeenCalledTimes(2);
    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/api/admin/content/approved-picker', { params: {} });
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/api/admin/content/approved-picker', { params: { cursor: 'k1' } });
  });

  it('keeps both fields the thumbnail falls back through, and the visibility', async () => {
    // getThumbnailUrl() reads thumbnailUrl first and otherwise builds a URL from youtubeId.
    vi.mocked(apiClient.get).mockResolvedValue({
      data: { items: [{ type: 'video', id: 'v1', title: 'A Video', thumbnailUrl: null, youtubeId: 'ytv1', visibility: 'PERSONAL' }] }
    } as never);

    const [first] = await getApprovedContent();

    expect(first).toHaveProperty('thumbnailUrl');
    expect(first.youtubeId).toBe('ytv1');
    expect(first.visibility).toBe('PERSONAL');
  });

  it('survives a response with no body fields', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: {} } as never);

    expect(await getApprovedContent()).toEqual([]);
    expect(apiClient.get).toHaveBeenCalledTimes(1);
  });
});
