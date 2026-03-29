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
    result.push({ ...parent, isSubcategory: false });
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

export interface ApprovedContentItem {
  type: string;
  id: string;
  youtubeId: string | null;
  title: string;
  description: string;
  thumbnailUrl: string | null;
  status: string;
  categoryIds: string[] | null;
  count: number | null;
}

interface ContentLibraryResponse {
  content: ApprovedContentItem[];
  totalItems: number;
  currentPage: number;
  pageSize: number;
  totalPages: number;
  truncated: boolean;
}

export interface ApprovedContentResult {
  items: ApprovedContentItem[];
  truncated: boolean;
}

export async function getApprovedContent(
  types?: string,
  search?: string
): Promise<ApprovedContentResult> {
  const allItems: ApprovedContentItem[] = [];
  let page = 0;
  let truncated = false;
  const pageSize = 100; // Backend caps at 100
  const maxPages = 20; // Safety cap: 2000 items max

  while (page < maxPages) {
    const params: Record<string, string> = {
      status: 'approved',
      size: String(pageSize),
      page: String(page)
    };
    if (types) params.types = types;
    if (search) params.search = search;

    const response = await apiClient.get<ContentLibraryResponse>('/api/admin/content', { params });
    const data = response.data;
    allItems.push(...data.content);

    if (data.truncated) {
      truncated = true;
    }

    // Stop if we've fetched all pages
    if (page >= data.totalPages - 1 || data.content.length < pageSize) {
      break;
    }
    page++;
  }

  // If we hit the page cap without exhausting pages, mark as truncated
  if (page >= maxPages) {
    truncated = true;
  }

  return { items: allItems, truncated };
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
