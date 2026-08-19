import apiClient from './api/client';

export interface CategorySortItem {
  id: string;
  name: string;
  icon: string | null;
  localizedNames: Record<string, string> | null;
  displayOrder: number;
  contentCount: number;
  parentCategoryId: string | null;
  subcategories?: CategorySortItem[];
}

/**
 * Build a hierarchical tree from a flat category list, then flatten it
 * back into a display-order list where subcategories follow their parent.
 */
export interface DisplayCategory extends CategorySortItem {
  isSubcategory: boolean;
}

export function buildDisplayCategories(flat: CategorySortItem[]): DisplayCategory[] {
  const map = new Map<string, CategorySortItem & { subcategories: CategorySortItem[] }>();
  const roots: (CategorySortItem & { subcategories: CategorySortItem[] })[] = [];

  for (const cat of flat) {
    map.set(cat.id, { ...cat, subcategories: [] });
  }

  for (const cat of flat) {
    const node = map.get(cat.id)!;
    if (cat.parentCategoryId) {
      const parent = map.get(cat.parentCategoryId);
      if (parent) {
        parent.subcategories.push(node);
      } else {
        roots.push(node);
      }
    } else {
      roots.push(node);
    }
  }

  const result: DisplayCategory[] = [];
  for (const parent of roots) {
    // Aggregate subcategory counts into the parent's contentCount
    const subTotal = parent.subcategories.reduce((sum, sub) => sum + sub.contentCount, 0);
    result.push({ ...parent, contentCount: parent.contentCount + subTotal, isSubcategory: false });
    for (const sub of parent.subcategories) {
      result.push({ ...sub, isSubcategory: true });
    }
  }
  return result;
}

export interface ContentSortItem {
  contentId: string;
  contentType: string;
  title: string;
  thumbnailUrl: string | null;
  position: number;
  youtubeId: string | null;
}

export async function getCategorySortOrder(): Promise<CategorySortItem[]> {
  const response = await apiClient.get<CategorySortItem[]>('/api/admin/sort/categories');
  return response.data;
}

export async function reorderCategory(categoryId: string, newPosition: number): Promise<CategorySortItem[]> {
  const response = await apiClient.put<CategorySortItem[]>('/api/admin/sort/categories/reorder', {
    categoryId,
    newPosition
  });
  return response.data;
}

export async function getCategoryContentOrder(categoryId: string): Promise<ContentSortItem[]> {
  const response = await apiClient.get<ContentSortItem[]>(`/api/admin/sort/categories/${categoryId}/content`);
  return response.data;
}

export async function reorderContentInCategory(
  categoryId: string,
  contentId: string,
  contentType: string,
  newPosition: number
): Promise<ContentSortItem[]> {
  const response = await apiClient.put<ContentSortItem[]>(
    `/api/admin/sort/categories/${categoryId}/content/reorder`,
    { contentId, contentType, newPosition }
  );
  return response.data;
}

export interface AddContentItem {
  contentId: string;
  contentType: string;
}

export async function addContentToCategorySort(
  categoryId: string,
  items: AddContentItem[]
): Promise<ContentSortItem[]> {
  const response = await apiClient.post<ContentSortItem[]>(
    `/api/admin/sort/categories/${categoryId}/content/add`,
    { items }
  );
  return response.data;
}

/**
 * One option in the "add content to a category" picker.
 *
 * Five fields, because that is what the picker draws. `thumbnailUrl` and `youtubeId` are both
 * load-bearing: `getThumbnailUrl()` reads the former and otherwise builds a URL from the latter,
 * so dropping either strips thumbnails from part of the list.
 */
export interface ApprovedContentItem {
  type: string;
  id: string;
  youtubeId: string | null;
  title: string;
  thumbnailUrl: string | null;
}

export interface ApprovedContentResult {
  items: ApprovedContentItem[];
  truncated: boolean;
}

/**
 * Every approved item the picker can offer, in one request.
 *
 * Previously assembled by paging `/api/admin/content` in a loop, which could not terminate on
 * its own: the server's `totalPages` describes its current fetch window, and that window grows by
 * one page per content type each round — so the finish line outran the loop and it ran to a
 * 20-page safety cap. Offset paging also re-read every earlier row on every request. At ~1,600
 * items that was 16 requests and an order of magnitude more records read than displayed.
 */
export async function getApprovedContent(): Promise<ApprovedContentResult> {
  const response = await apiClient.get<ApprovedContentResult>('/api/admin/content/approved-picker');
  return {
    items: response.data.items ?? [],
    truncated: response.data.truncated ?? false
  };
}

export async function removeContentFromCategorySort(
  categoryId: string,
  contentType: string,
  contentId: string
): Promise<ContentSortItem[]> {
  const response = await apiClient.delete<ContentSortItem[]>(
    `/api/admin/sort/categories/${categoryId}/content/${contentType}/${contentId}`
  );
  return response.data;
}
