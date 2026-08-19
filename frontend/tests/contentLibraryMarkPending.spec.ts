/**
 * "Mark as Pending" in the Content Library was wired to the reject endpoint, because none existed
 * for putting content back under review. A button that says it returns an item to the queue was
 * recording it as rejected — and once rejecting began clearing content from people's phones, that
 * same button would have taken it off every device holding it.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import * as contentLibrary from '@/services/contentLibrary';
import { authorizedJsonFetch } from '@/services/http';

vi.mock('@/services/http', () => ({
  authorizedJsonFetch: vi.fn().mockResolvedValue({ successCount: 1, errors: [] })
}));

describe('contentLibrary bulk status actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(authorizedJsonFetch).mockResolvedValue({ successCount: 1, errors: [] } as never);
  });

  const items = [{ type: 'video', id: 'v1' }];

  function urlsCalled() {
    return vi.mocked(authorizedJsonFetch).mock.calls.map(c => c[0]);
  }

  it('marking pending posts to the pending endpoint', async () => {
    await contentLibrary.bulkMarkPending(items);

    expect(urlsCalled()).toContain('/api/admin/content/bulk/pending');
  });

  it('marking pending never posts to reject', async () => {
    await contentLibrary.bulkMarkPending(items);

    expect(urlsCalled()).not.toContain('/api/admin/content/bulk/reject');
  });

  it('approving still posts to approve', async () => {
    await contentLibrary.bulkApprove(items);

    expect(urlsCalled()).toContain('/api/admin/content/bulk/approve');
  });
});
