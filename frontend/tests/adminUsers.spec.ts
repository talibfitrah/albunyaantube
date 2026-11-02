import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@/services/http', () => ({ authorizedJsonFetch: vi.fn() }));

import { authorizedJsonFetch } from '@/services/http';
import { fetchUsersPage } from '@/services/adminUsers';

const fetchMock = authorizedJsonFetch as unknown as ReturnType<typeof vi.fn>;

// The backend returns every user and ignores search/role/status, and sends
// createdAt as a Firestore {seconds, nanos} object.
const backendUsers = [
  { uid: 'u1', email: 'maroc_example@hotmail.com', role: 'user', status: 'active', displayName: 'Maroc',
    createdAt: { seconds: 1793522777, nanos: 326000000 } },
  { uid: 'u2', email: 'other@gmail.com', role: 'moderator', status: 'active', displayName: null,
    createdAt: '2026-10-01T10:00:00Z' },
  { uid: 'u3', email: 'blocked@gmail.com', role: 'user', status: 'blocked', displayName: null,
    createdAt: { seconds: 1793522777, nanos: 0 } }
];

describe('fetchUsersPage', () => {
  beforeEach(() => fetchMock.mockResolvedValue(backendUsers));

  it('filters by search text, case-insensitively, on email', async () => {
    const page = await fetchUsersPage({ search: 'Maroc_Example@Hotmail.com' });
    expect(page.data.map(u => u.id)).toEqual(['u1']);
  });

  it('filters by role and status', async () => {
    expect((await fetchUsersPage({ role: 'MODERATOR' })).data.map(u => u.id)).toEqual(['u2']);
    expect((await fetchUsersPage({ status: 'BLOCKED' })).data.map(u => u.id)).toEqual(['u3']);
  });

  it('turns a {seconds, nanos} createdAt into an ISO string', async () => {
    const page = await fetchUsersPage();
    expect(page.data[0].createdAt).toBe('2026-11-01T08:46:17.326Z');
    expect(page.data[1].createdAt).toBe('2026-10-01T10:00:00.000Z');
  });
});
