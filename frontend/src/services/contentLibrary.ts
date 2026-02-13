import { authorizedJsonFetch } from '@/services/http';
import type { BulkActionItem, BulkActionResponse } from '@/types/api';

/**
 * Bulk approve content items
 */
export async function bulkApprove(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/api/admin/content/bulk/approve', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk reject content items
 */
export async function bulkReject(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/api/admin/content/bulk/reject', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk delete content items
 */
export async function bulkDelete(items: BulkActionItem[]): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/api/admin/content/bulk/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items })
  });
}

/**
 * Bulk assign categories to content items
 */
export async function bulkAssignCategories(
  items: BulkActionItem[],
  categoryIds: string[]
): Promise<BulkActionResponse> {
  return await authorizedJsonFetch('/api/admin/content/bulk/assign-categories', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items, categoryIds })
  });
}

/**
 * Reorder item for bulk reorder operation
 */
export interface ReorderItem {
  type: 'channel' | 'playlist' | 'video';
  id: string;
  displayOrder: number;
}

/**
 * Bulk reorder content items (update display order)
 *
 * HTTP Semantics:
 * - 200: All items reordered successfully
 * - 400: Client error (missing items) - no mutations occurred
 * - 503: Server error (timeout/Firestore failure) - partial mutations may have occurred
 *
 * Unlike other bulk operations, reorder uses atomic rejection:
 * if any item is missing, the entire request is rejected before any mutations.
 */
export async function bulkReorder(items: ReorderItem[]): Promise<BulkActionResponse> {
  // Import auth store for token
  const { useAuthStore } = await import('@/stores/auth');
  const authStore = useAuthStore();

  const url = '/api/admin/content/bulk/reorder';
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    'Accept': 'application/json'
  };

  if (authStore.idToken) {
    (headers as Record<string, string>)['Authorization'] = `Bearer ${authStore.idToken}`;
  }

  const options: RequestInit = {
    method: 'POST',
    headers,
    body: JSON.stringify({ items })
  };

  // Use a custom fetch that handles 400/503 responses with body parsing
  const response = await fetch(url, options);

  // Handle 401 with token refresh (same as authorizedJsonFetch)
  if (response.status === 401) {
    const refreshed = await authStore.refreshToken();
    if (refreshed) {
      // Retry with new token
      (headers as Record<string, string>)['Authorization'] = `Bearer ${authStore.idToken}`;
      const retryResponse = await fetch(url, { ...options, headers });
      if (!retryResponse.ok && retryResponse.status !== 400 && retryResponse.status !== 503) {
        throw new Error(`Request failed after token refresh: ${retryResponse.status}`);
      }
      return await retryResponse.json() as BulkActionResponse;
    } else {
      throw new Error('Authentication failed: Unable to refresh token');
    }
  }

  // Parse response body for all status codes (200, 400, 503 all return BulkActionResponse)
  const body = await response.json() as BulkActionResponse;

  // Return the body regardless of status code - caller handles errors via successCount/errors
  return body;
}
