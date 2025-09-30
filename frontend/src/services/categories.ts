import { authorizedJsonFetch } from '@/services/http';

interface SubcategoryResponse {
  id: string;
  slug: string;
  name: Record<string, string>;
}

interface CategoryResponse {
  id: string;
  slug: string;
  name: Record<string, string>;
  description: Record<string, string>;
  subcategories: SubcategoryResponse[];
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
  subcategories?: CategoryOption[];
}

export async function fetchAllCategories(limit = 100): Promise<CategoryOption[]> {
  const response = await authorizedJsonFetch<CategoryPageResponse>(
    `/api/v1/admins/categories?limit=${limit}`
  );
  return response.data.map(mapCategoryResponse);
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

function mapCategoryResponse(category: CategoryResponse): CategoryOption {
  return {
    id: category.id,
    slug: category.slug,
    label: resolveLabel(category.name),
    subcategories: category.subcategories?.map(subcategory => ({
      id: subcategory.id,
      slug: subcategory.slug,
      label: resolveLabel(subcategory.name)
    }))
  };
}
