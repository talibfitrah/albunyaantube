import { authorizedJsonFetch } from '@/services/http';

interface CategoryResponse {
  id: string;
  slug: string;
  name: Record<string, string>;
}

interface CategoryPageResponse {
  data: CategoryResponse[];
  pageInfo: {
    nextCursor: string | null;
  };
}

export interface CategoryOption {
  id: string;
  slug: string;
  label: string;
}

export async function fetchAllCategories(limit = 100): Promise<CategoryOption[]> {
  const response = await authorizedJsonFetch<CategoryPageResponse>(
    `/api/v1/admins/categories?limit=${limit}`
  );
  return response.data.map(category => ({
    id: category.id,
    slug: category.slug,
    label: resolveLabel(category.name)
  }));
}

function resolveLabel(names: Record<string, string>): string {
  if (!names) {
    return '';
  }
  if (names.en) {
    return names.en;
  }
  const firstEntry = Object.values(names)[0];
  return firstEntry ?? '';
}
