import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

interface AdminUser {
  id: string;
  email: string;
  roles: Array<'ADMIN' | 'MODERATOR'>;
  status: 'ACTIVE' | 'DISABLED';
  lastLoginAt: string | null;
  createdAt: string;
  updatedAt: string;
}

interface AuditEvent {
  id: string;
  actorEmail: string;
  action: string;
  entityId: string;
  metadata: Record<string, unknown>;
  createdAt: string;
}

test.describe('Admin users & audit log', () => {
  test('supports user CRUD and shows audit entries', async ({ page }) => {
    await page.addInitScript(() => {
      // no-op to keep init script signature consistent if future globals needed
    });

    const users: AdminUser[] = [
      {
        id: 'user-1',
        email: 'admin@example.com',
        roles: ['ADMIN'],
        status: 'ACTIVE',
        lastLoginAt: new Date('2025-09-20T12:00:00Z').toISOString(),
        createdAt: new Date('2025-09-10T08:00:00Z').toISOString(),
        updatedAt: new Date('2025-09-20T08:00:00Z').toISOString()
      },
      {
        id: 'user-2',
        email: 'moderator@example.com',
        roles: ['MODERATOR'],
        status: 'DISABLED',
        lastLoginAt: null,
        createdAt: new Date('2025-09-15T08:00:00Z').toISOString(),
        updatedAt: new Date('2025-09-20T08:00:00Z').toISOString()
      }
    ];

    const auditLog: AuditEvent[] = [
      {
        id: 'audit-1',
        actorEmail: 'system@albunyaan.tube',
        action: 'users:seed',
        entityId: 'seed',
        metadata: {},
        createdAt: new Date('2025-09-01T00:00:00Z').toISOString()
      }
    ];

    await page.route('**/api/v1/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          tokenType: 'Bearer',
          accessToken: 'test-token',
          refreshToken: 'test-refresh',
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
          accessToken: 'refreshed-token',
          refreshToken: 'test-refresh',
          accessTokenExpiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
          refreshTokenExpiresAt: new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString(),
          roles: ['ADMIN']
        })
      });
    });

    await page.route('**/api/v1/admin/users**', async (route) => {
      const request = route.request();
      const method = request.method();

      if (method === 'GET') {
        const url = new URL(request.url());
        const search = (url.searchParams.get('search') ?? '').toLowerCase();
        const role = url.searchParams.get('role');
        const status = url.searchParams.get('status');

        let data = users.slice();
        if (search) {
          data = data.filter((user) => user.email.toLowerCase().includes(search) || user.id.toLowerCase().includes(search));
        }
        if (role) {
          data = data.filter((user) => user.roles.includes(role as 'ADMIN' | 'MODERATOR'));
        }
        if (status) {
          data = data.filter((user) => user.status === status);
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
        const payload = (await request.postDataJSON()) as { email: string; roles: Array<'ADMIN' | 'MODERATOR'> };
        const newUser: AdminUser = {
          id: `user-${Date.now()}`,
          email: payload.email,
          roles: payload.roles,
          status: 'ACTIVE',
          lastLoginAt: null,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
        users.unshift(newUser);
        auditLog.unshift({
          id: `audit-${Date.now()}`,
          actorEmail: 'admin@example.com',
          action: 'users:create',
          entityId: newUser.id,
          metadata: { email: newUser.email },
          createdAt: new Date().toISOString()
        });
        await route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify(newUser)
        });
        return;
      }

      if (method === 'DELETE') {
        const id = request.url().split('/').pop() ?? '';
        const index = users.findIndex((user) => user.id === id);
        if (index !== -1) {
          users[index] = {
            ...users[index],
            status: 'DISABLED',
            updatedAt: new Date().toISOString()
          };
          auditLog.unshift({
            id: `audit-${Date.now()}`,
            actorEmail: 'admin@example.com',
            action: 'users:deactivate',
            entityId: id,
            metadata: {},
            createdAt: new Date().toISOString()
          });
        }
        await route.fulfill({ status: 204 });
        return;
      }

      await route.fallback();
    });

    await page.route('**/api/v1/admin/audit**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: auditLog.map((event) => ({
            id: event.id,
            actor: {
              id: `actor-${event.id}`,
              email: event.actorEmail,
              roles: ['ADMIN'],
              status: 'ACTIVE',
              lastLoginAt: null,
              createdAt: event.createdAt,
              updatedAt: event.createdAt
            },
            action: event.action,
            entity: {
              type: 'USER',
              id: event.entityId,
              slug: null
            },
            metadata: event.metadata,
            createdAt: event.createdAt
          })),
          pageInfo: {
            cursor: null,
            nextCursor: null,
            hasNext: false,
            limit: auditLog.length
          }
        })
      });
    });

    await page.goto('/login');
    await page.getByLabel('Work email', { exact: false }).fill('admin@example.com');
    await page.getByLabel('Password', { exact: false }).fill('supersecure');
    await Promise.all([
      page.waitForRequest((request) => request.url().includes('/api/v1/auth/login') && request.method() === 'POST'),
      page.getByRole('button', { name: /sign in/i }).click()
    ]);

    await page.goto('/users');
    await expect(page.getByRole('heading', { name: /user management/i })).toBeVisible();
    await page.waitForSelector('text=admin@example.com');

    const axeBuilder = new AxeBuilder({ page }).include('main');
    const results = await axeBuilder.analyze();
    const filteredViolations = results.violations.filter((violation) => violation.id !== 'color-contrast');
    expect(filteredViolations).toEqual([]);

    await expect(page.getByText('admin@example.com')).toBeVisible();

    await page.getByRole('button', { name: /add user/i }).click();
    const createDialog = page.getByRole('dialog', { name: /invite admin or moderator/i });
    await createDialog.getByLabel('Work email').fill('new-user@example.com');
    await createDialog.getByLabel('Moderator', { exact: false }).check();
    const createResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/api/v1/admin/users') && response.request().method() === 'POST'
    );
    await createDialog.getByRole('button', { name: /create user/i }).click();
    const createdUser = (await createResponsePromise).json() as Promise<{ id: string }>;
    const { id: newUserId } = await createdUser;
    const newUserRow = page.locator('tr', { hasText: 'new-user@example.com' });
    await expect(newUserRow).toBeVisible();
    await page.waitForSelector('.modal-backdrop', { state: 'hidden' });

    const deleteRequestPromise = page.waitForRequest(
      (request) => request.url().includes(`/api/v1/admin/users/${newUserId}`) && request.method() === 'DELETE'
    );
    await newUserRow.getByRole('button', { name: /deactivate/i }).click();
    await deleteRequestPromise;

    await page.goto('/audit');
    await expect(page.getByRole('heading', { name: /audit log/i })).toBeVisible();
    await page.waitForSelector('text=users:create');
    await expect(page.getByText('users:create')).toBeVisible();
  });
});
