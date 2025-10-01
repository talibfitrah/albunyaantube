import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

interface MockExclusion {
  id: string;
  parentType: 'CHANNEL' | 'PLAYLIST';
  parentId: string;
  excludeType: 'PLAYLIST' | 'VIDEO';
  excludeId: string;
  reason: string;
  createdAt: string;
  createdBy: { email: string };
}

test.describe('Exclusions workspace e2e', () => {
  test('supports search, editing and deletion with accessible markup', async ({ page }) => {
    const tableState: MockExclusion[] = [
      {
        id: 'exclusion-1',
        parentType: 'CHANNEL',
        parentId: 'channel:alqalam',
        excludeType: 'PLAYLIST',
        excludeId: 'playlist:alqalam-foundation',
        reason: 'Manual removal pending QA',
        createdAt: '2025-09-20T08:30:00Z',
        createdBy: { email: 'admin@example.com' }
      },
      {
        id: 'exclusion-2',
        parentType: 'PLAYLIST',
        parentId: 'playlist:series-halaqa',
        excludeType: 'VIDEO',
        excludeId: 'video:daily-halaqa-231',
        reason: 'Contains unrelated segment',
        createdAt: '2025-09-22T10:12:00Z',
        createdBy: { email: 'moderator@example.com' }
      }
    ];

    let lastCreatedId: string | null = null;

    await page.addInitScript(() => {
      (window as unknown as { __AUDIT_EVENTS__?: Array<Record<string, unknown>> }).__AUDIT_EVENTS__ = [];
      window.addEventListener('admin:audit', (event: Event) => {
        const custom = event as CustomEvent;
        (window as unknown as { __AUDIT_EVENTS__?: Array<Record<string, unknown>> }).__AUDIT_EVENTS__?.push(
          custom.detail as Record<string, unknown>
        );
      });
    });

    await page.route('**/api/v1/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          tokenType: 'Bearer',
          accessToken: 'test-access-token',
          refreshToken: 'test-refresh-token',
          accessTokenExpiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
          refreshTokenExpiresAt: new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
          roles: ['ADMIN']
        })
      });
    });

    await page.route('**/api/v1/auth/refresh', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          tokenType: 'Bearer',
          accessToken: 'refreshed-access-token',
          refreshToken: 'test-refresh-token',
          accessTokenExpiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
          refreshTokenExpiresAt: new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
          roles: ['ADMIN']
        })
      });
    });

    await page.route('**/api/v1/admins/dashboard**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totals: { pending: 0, approved: 0, rejected: 0 },
          recentActivity: []
        })
      });
    });

    await page.route(/\/api\/v1\/exclusions(?:\/[^?]*)?(?:\?.*)?$/, async (route) => {
      const request = route.request();
      const method = request.method();

      if (method === 'GET') {
        const url = new URL(request.url());
        const search = (url.searchParams.get('search') ?? '').toLowerCase();
        const parentType = url.searchParams.get('parentType');
        const excludeType = url.searchParams.get('excludeType');

        let data = tableState.slice();
        if (search) {
          data = data.filter((entry) => entry.excludeId.toLowerCase().includes(search));
        }
        if (parentType) {
          data = data.filter((entry) => entry.parentType === parentType);
        }
        if (excludeType) {
          data = data.filter((entry) => entry.excludeType === excludeType);
        }

        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            data,
            pageInfo: {
              cursor: null,
              nextCursor: null,
              hasNext: false,
              limit: data.length
            }
          })
        });
        return;
      }

      if (method === 'POST') {
        const payload = (await request.postDataJSON()) as {
          parentType: 'CHANNEL' | 'PLAYLIST';
          parentId: string;
          excludeType: 'PLAYLIST' | 'VIDEO';
          excludeId: string;
          reason: string;
        };
        const created: MockExclusion = {
          id: `exclusion-${Date.now()}`,
          parentType: payload.parentType,
          parentId: payload.parentId,
          excludeType: payload.excludeType,
          excludeId: payload.excludeId,
          reason: payload.reason,
          createdAt: new Date().toISOString(),
          createdBy: { email: 'admin@example.com' }
        };
        tableState.unshift(created);
        lastCreatedId = created.id;
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(created)
        });
        return;
      }

      if (method === 'PATCH') {
        const id = request.url().split('/').pop() ?? '';
        const payload = (await request.postDataJSON()) as { reason: string };
        const index = tableState.findIndex((entry) => entry.id === id);
        if (index === -1) {
          await route.fulfill({ status: 404 });
          return;
        }
        tableState[index] = {
          ...tableState[index],
          reason: payload.reason,
          createdAt: new Date().toISOString()
        };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(tableState[index])
        });
        return;
      }

      if (method === 'DELETE') {
        const id = request.url().split('/').pop() ?? '';
        const index = tableState.findIndex((entry) => entry.id === id);
        if (index >= 0) {
          tableState.splice(index, 1);
        }
        await route.fulfill({ status: 204 });
        return;
      }

      await route.fallback();
    });

    await page.goto('/login');
    await page.getByLabel('Work email', { exact: false }).fill('admin@example.com');
    await page.getByLabel('Password', { exact: false }).fill('supersecure');
    await Promise.all([
      page.waitForRequest(
        (request) => request.url().includes('/api/v1/auth/login') && request.method() === 'POST'
      ),
      page.getByRole('button', { name: /sign in/i }).click()
    ]);
    await expect(page.getByRole('heading', { name: /salaam/i })).toBeVisible();

    await page.goto('/exclusions');
    await expect(page.getByRole('heading', { name: /exclusions workspace/i })).toBeVisible();

    const axeBuilder = new AxeBuilder({ page }).include('main');
    const accessibilityResults = await axeBuilder.analyze();
    const filteredViolations = accessibilityResults.violations.filter(
      (violation) => violation.id !== 'color-contrast'
    );
    expect(filteredViolations).toEqual([]);

    await expect(page.getByText('playlist:alqalam-foundation')).toBeVisible();

    await page.getByRole('searchbox', { name: /search exclusions/i }).fill('daily');
    await page.waitForRequest(
      (request) =>
        request.url().includes('/api/v1/exclusions') &&
        request.method() === 'GET' &&
        request.url().includes('search=daily')
    );
    await expect(page.getByText('video:daily-halaqa-231')).toBeVisible();
    await expect(page.locator('text=playlist:alqalam-foundation')).toHaveCount(0);

    await page.getByRole('button', { name: /edit/i }).first().click();
    const reasonField = page.getByLabel('Reason', { exact: false });
    await reasonField.fill('Updated QA note');
    await Promise.all([
      page.waitForRequest(
        (request) =>
          request.url().includes('/api/v1/exclusions/exclusion-2') && request.method() === 'PATCH'
      ),
      page.waitForResponse(
        (response) =>
          response.url().includes('/api/v1/exclusions/exclusion-2') && response.request().method() === 'PATCH'
      ),
      page.getByRole('button', { name: /update exclusion/i }).click()
    ]);
    await expect(page.locator('.modal-backdrop')).toHaveCount(0);

    await page.getByRole('checkbox', { name: /select video:daily-halaqa-231/i }).check();
    await Promise.all([
      page.waitForRequest(
        (request) =>
          request.url().includes('/api/v1/exclusions/exclusion-2') && request.method() === 'DELETE'
      ),
      page.waitForResponse(
        (response) =>
          response.url().includes('/api/v1/exclusions/exclusion-2') && response.request().method() === 'DELETE'
      ),
      page.getByRole('button', { name: /remove selected/i }).click()
    ]);

    await page.getByRole('button', { name: /add exclusion/i }).click();
    await page.getByLabel('Parent ID', { exact: false }).fill('channel:new');
    await page.getByLabel('Excluded ID', { exact: false }).fill('video:new');
    await page.getByLabel('Reason', { exact: false }).fill('Manual verification');
    await Promise.all([
      page.waitForRequest(
        (request) => request.url().includes('/api/v1/exclusions') && request.method() === 'POST'
      ),
      page.waitForResponse(
        (response) => response.url().includes('/api/v1/exclusions') && response.request().method() === 'POST'
      ),
      page.getByRole('button', { name: /create exclusion/i }).click()
    ]);
    await expect(page.getByText('video:new')).toBeVisible();

    const capturedEvents = await page.evaluate(() =>
      (window as unknown as { __AUDIT_EVENTS__?: Array<Record<string, unknown>> }).__AUDIT_EVENTS__ ?? []
    );

    expect(capturedEvents).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          name: 'exclusions:create',
          exclusionId: lastCreatedId
        }),
        expect.objectContaining({
          name: 'exclusions:update',
          exclusionId: 'exclusion-2',
          metadata: expect.objectContaining({ reason: 'Updated QA note' })
        }),
        expect.objectContaining({
          name: 'exclusions:delete-many',
          metadata: expect.objectContaining({ count: 1 })
        })
      ])
    );
  });
});
