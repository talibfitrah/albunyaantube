import '@testing-library/jest-dom';
import { render, screen, fireEvent } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import ActivityLogView from '@/views/ActivityLogView.vue';
import { fetchAuditLogPage } from '@/services/adminAudit';
import { messages } from '@/locales/messages';

const authorizedJsonFetchMock = vi.fn();

vi.mock('@/services/http', () => ({
  authorizedJsonFetch: (...args: unknown[]) => authorizedJsonFetchMock(...args)
}));

/**
 * Verbatim page from GET /api/admin/audit in production: the backend
 * serialises com.google.cloud.Timestamp as {seconds, nanos}, not as the ISO
 * string the OpenAPI spec declares.
 */
const auditPage = {
  items: [
    {
      id: 'Q2SGQlihwY3wj2gbX26h',
      action: 'video_rejected',
      entityType: 'video',
      entityId: 'Vk8FZo9oVro',
      actorUid: 'svo08aaetJYw2x6Tn7AAvm4f6w03',
      actorDisplayName: 'info@fitrahmedia.nl',
      details: {},
      timestamp: { seconds: 1787153109, nanos: 503000000 },
      ipAddress: null
    }
  ],
  nextCursor: null
};

describe('ActivityLogView', () => {
  beforeEach(() => {
    authorizedJsonFetchMock.mockReset();
    authorizedJsonFetchMock.mockResolvedValue(structuredClone(auditPage));
  });

  it('maps the backend {seconds, nanos} timestamp to an ISO string', async () => {
    const page = await fetchAuditLogPage();

    expect(page.data).toHaveLength(1);
    expect(page.data[0].timestamp).toBe('2026-08-19T15:25:09.503Z');
  });

  it.each([
    ['out-of-range seconds', { seconds: 1e16, nanos: 0 }],
    ['an unparseable string', 'not a date']
  ])('drops a row whose timestamp is %s', async (_label, timestamp) => {
    const undatable = structuredClone(auditPage);
    undatable.items[0].timestamp = timestamp as never;
    authorizedJsonFetchMock.mockResolvedValue(undatable);

    await expect(fetchAuditLogPage()).resolves.toMatchObject({ data: [] });
  });

  it('keeps a row whose timestamp is already an ISO string', async () => {
    const isoPage = structuredClone(auditPage);
    isoPage.items[0].timestamp = '2026-08-19T15:25:09.503Z' as never;
    authorizedJsonFetchMock.mockResolvedValue(isoPage);

    const page = await fetchAuditLogPage();

    expect(page.data[0].timestamp).toBe('2026-08-19T15:25:09.503Z');
  });

  it('filters by the actor email the log displays, not by uid', async () => {
    render(ActivityLogView, {
      global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] }
    });
    await screen.findAllByText('video_rejected');

    await fireEvent.update(screen.getByLabelText('Filter by User'), 'info@fitrahmedia.nl');
    await vi.waitFor(() =>
      expect(authorizedJsonFetchMock).toHaveBeenCalledWith(
        expect.stringContaining('actorEmail=info%40fitrahmedia.nl'),
        expect.anything()
      )
    );
  });

  it('renders the entry in the timeline, identified by username and not by uid', async () => {
    render(ActivityLogView, {
      global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] }
    });

    // Timeline is the default view. Its groupedByDate computed calls
    // toISOString() on every entry timestamp, so a non-ISO timestamp aborts
    // the update render and the page stays stuck on its empty state.
    expect((await screen.findAllByText('video_rejected')).length).toBeGreaterThan(0);
    expect(screen.getByText('info@fitrahmedia.nl')).toBeInTheDocument();
    expect(screen.queryByText(/svo08aaetJYw2x/)).toBeNull();
    expect(screen.queryByText(/Invalid Date/)).toBeNull();
  });
});
