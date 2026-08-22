/**
 * The bug this pins is a layout one: mutating a row rebuilt the list from page 0, the document
 * shrank, and the browser clamped the scroll offset back near the top of a list the admin had
 * scrolled a long way down. The unit suite mounts the real component but runs in jsdom, which has
 * no layout — it can prove the rows survive, not that the scrollbar stays put. This boots the real
 * app in a real browser and measures the scroll offset across a mutation.
 */
import { test, expect, type Locator, type Page } from '@playwright/test';

const HARNESS = '/tests/e2e-browser/harness.html';
const PAGE_SIZE = 25;
const TOTAL = 120;

interface Row {
  type: string;
  id: string;
  youtubeId: string;
  title: string;
  status: string;
  categoryIds: string[];
  createdAt: string;
  visibility: string;
  grantedTo: string[];
}

function makeLibrary(count: number): Row[] {
  return Array.from({ length: count }, (_, i) => ({
    type: 'video',
    id: `v${i + 1}`,
    youtubeId: `yt${i + 1}`,
    title: `Video ${i + 1}`,
    status: 'PENDING',
    categoryIds: [],
    // Descending, so the server's newest-first order is Video 1 .. Video N.
    createdAt: new Date(Date.UTC(2026, 0, 1) - i * 60_000).toISOString(),
    visibility: 'PUBLIC',
    grantedTo: []
  }));
}

/**
 * The document scrolls, not AdminLayout's <main>. The layout gives `main` `overflow-y: auto`, but
 * never constrains its height, so it grows with the list and the window is what moves. Measured,
 * not assumed: with 100 rows loaded nothing in the page reports scrollHeight > clientHeight except
 * the document itself.
 */
function scrollTop(page: Page): Promise<number> {
  return page.evaluate(() => window.scrollY);
}

/** Title of the first row or card whose bottom edge is still below the top of the viewport. */
function topVisibleTitle(page: Page, selector: string): Promise<string | null> {
  return page.evaluate(sel => {
    const items = Array.from(document.querySelectorAll(sel));
    const first = items.find(item => item.getBoundingClientRect().bottom > 1);
    return first?.textContent?.trim() ?? null;
  }, selector);
}

/**
 * Scroll so the named row sits at the top of the viewport. Assigning the offset lands
 * synchronously, so there is nothing to wait on and no sleep to go flaky on a loaded runner.
 */
async function parkAt(page: Page, title: string, titleSelector: string) {
  await page.evaluate(
    ({ wanted, sel }) => {
      const node = Array.from(document.querySelectorAll(sel))
        .find(candidate => candidate.textContent?.trim() === wanted)!;
      window.scrollTo({ top: window.scrollY + node.getBoundingClientRect().top, behavior: 'instant' });
    },
    { wanted: title, sel: titleSelector }
  );
}

test.describe('Content Library scroll position', () => {
  let listingRequests = 0;

  test.beforeEach(async ({ page }) => {
    const library = makeLibrary(TOTAL);
    listingRequests = 0;

    // Safety net, registered first so it has the lowest priority: nothing in this test may reach
    // an origin other than the local dev server. A stray call would hit the real backend.
    await page.route('**/*', route => {
      const host = new URL(route.request().url()).hostname;
      return host === '127.0.0.1' || host === 'localhost' ? route.fallback() : route.abort();
    });

    await page.route(url => url.pathname === '/api/admin/categories', route =>
      route.fulfill({ json: [] }));

    await page.route(url => url.pathname === '/api/admin/content/totals', route =>
      route.fulfill({ json: { channels: 0, playlists: 0, videos: library.length } }));

    await page.route(url => url.pathname === '/api/admin/content', route => {
      listingRequests += 1;
      const params = new URL(route.request().url()).searchParams;
      const page_ = Number(params.get('page') ?? 0);
      const size = Number(params.get('size') ?? PAGE_SIZE);
      return route.fulfill({
        json: {
          content: library.slice(page_ * size, page_ * size + size),
          totalItems: library.length,
          truncated: false
        }
      });
    });

    await page.route(url => url.pathname === '/api/admin/content/bulk/delete', route => {
      const { items } = JSON.parse(route.request().postData() ?? '{"items":[]}');
      for (const item of items as { id: string }[]) {
        const at = library.findIndex(row => row.id === item.id);
        if (at >= 0) library.splice(at, 1);
      }
      return route.fulfill({ json: { successCount: items.length, errors: [] } });
    });

    await page.route(url => url.pathname === '/api/admin/content/bulk/approve', route => {
      const { items } = JSON.parse(route.request().postData() ?? '{"items":[]}');
      for (const item of items as { id: string }[]) {
        const row = library.find(candidate => candidate.id === item.id);
        // Mirrors executeBulkStatusUpdate: approving publishes, so visibility goes with status.
        if (row) Object.assign(row, { status: 'APPROVED', visibility: 'PUBLIC', grantedTo: [] });
      }
      return route.fulfill({ json: { successCount: items.length, errors: [] } });
    });

    // confirm() on delete, alert() on success.
    page.on('dialog', dialog => dialog.accept());
  });

  /** Load four pages the way an admin does, then park mid-list. */
  async function scrollDeep(page: Page, titleSelector: string) {
    await page.goto(HARNESS);
    await expect(page.getByText('Video 1', { exact: true })).toBeVisible();
    for (let i = 0; i < 3; i++) {
      await page.getByRole('button', { name: /Load More/i }).click();
    }
    await expect(page.getByText('Video 100', { exact: true })).toBeAttached();

    // Mid-list, so the measurement cannot be confused with the browser clamping an offset that
    // ran past the end of a shorter document.
    await parkAt(page, 'Video 50', titleSelector);
    expect(await scrollTop(page)).toBeGreaterThan(500);
  }

  /** Where the admin is at the moment they press a control, after it has been scrolled to. */
  async function positionAt(page: Page, control: Locator, titleSelector: string) {
    await control.scrollIntoViewIfNeeded();
    const offset = await scrollTop(page);
    expect(offset).toBeGreaterThan(500);
    return { offset, topTitle: await topVisibleTitle(page, titleSelector) };
  }

  const ROW_TITLES = '.content-table tbody tr .title-text';
  const CARD_TITLES = '.content-cards .card-title';

  test('holds position when a row deep in the list is deleted', async ({ page }) => {
    await scrollDeep(page, ROW_TITLES);
    const control = page.locator('.content-table tbody tr', { hasText: 'Video 52' }).getByTitle('Delete');
    const before = await positionAt(page, control, ROW_TITLES);

    await control.click();
    await expect(page.getByText('Video 52', { exact: true })).toHaveCount(0);

    // One row left the document, so the offset can shift by at most that row's height — never
    // back up the list.
    expect(Math.abs(await scrollTop(page) - before.offset)).toBeLessThan(80);
    expect(await topVisibleTitle(page, ROW_TITLES)).toBe(before.topTitle);
    await expect(page.getByText('Video 100', { exact: true })).toBeAttached();
  });

  test('keeps the loaded pages when a row deep in the list is approved', async ({ page }) => {
    await scrollDeep(page, ROW_TITLES);
    const loadedBefore = listingRequests;

    const target = page.locator('.content-table tbody tr', { hasText: 'Video 52' });
    await target.getByRole('checkbox').check();
    await page.getByRole('button', { name: 'Bulk Actions' }).click();
    await page.getByRole('button', { name: 'Approve Selected' }).click();
    await expect(target.getByText('Approved', { exact: true })).toBeVisible();

    // Scroll offset is not the assertion here, and cannot be: the Bulk Actions button lives in a
    // header that is not sticky, so reaching it scrolls to the top of the page whatever the list
    // does. What the fix owns is that the four pages are still loaded afterwards rather than
    // rebuilt as one — so scrolling back down finds the same list.
    expect(await page.locator('.content-table tbody tr').count()).toBe(100);
    expect(listingRequests).toBe(loadedBefore);
    await expect(page.getByText('Video 100', { exact: true })).toBeAttached();
  });

  test('holds position on the card layout below the desktop breakpoint', async ({ page }) => {
    // Under 1024px the view renders cards instead of the table — a separate template with its own
    // delete button, and one the unit suite never reaches (jsdom reports 1024px wide).
    await page.setViewportSize({ width: 820, height: 720 });
    await scrollDeep(page, CARD_TITLES);
    const control = page.locator('.content-card', { hasText: 'Video 52' })
      .getByRole('button', { name: /Delete/i });
    const before = await positionAt(page, control, CARD_TITLES);

    await control.click();
    await expect(page.getByText('Video 52', { exact: true })).toHaveCount(0);

    // Cards are ~357px tall, so the bound is one card rather than one table row.
    expect(Math.abs(await scrollTop(page) - before.offset)).toBeLessThan(360);
    // A card is tall enough that reaching its delete button puts it at the top of the viewport,
    // so deleting it hands that slot to its successor — not to a card hundreds of rows up.
    expect(before.topTitle).toBe('Video 52');
    expect(await topVisibleTitle(page, CARD_TITLES)).toBe('Video 53');
    await expect(page.getByText('Video 100', { exact: true })).toBeAttached();
  });
});
