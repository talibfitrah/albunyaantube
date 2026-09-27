/**
 * setVideoOfflineAllowed is the first caller of the admin video PUT. The view test mocks the whole
 * service, so a typo in the method, path, or body would still pass there — this pins the wire call.
 */
import { describe, it, expect, vi } from 'vitest';
import { setVideoOfflineAllowed } from '@/services/contentLibrary';
import { authorizedJsonFetch } from '@/services/http';

vi.mock('@/services/http', () => ({
  authorizedJsonFetch: vi.fn().mockResolvedValue(undefined)
}));

describe('contentLibrary.setVideoOfflineAllowed', () => {
  it('PUTs {offlineAllowed} to the registry video endpoint', async () => {
    await setVideoOfflineAllowed('vid-123', false);

    expect(authorizedJsonFetch).toHaveBeenCalledTimes(1);
    const [url, init] = vi.mocked(authorizedJsonFetch).mock.calls[0];
    expect(url).toBe('/api/admin/registry/videos/vid-123');
    expect(init?.method).toBe('PUT');
    expect(JSON.parse(init?.body as string)).toEqual({ offlineAllowed: false });
  });
});
