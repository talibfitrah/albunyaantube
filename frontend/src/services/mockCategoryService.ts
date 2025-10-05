/**
 * Mock Category Service
 * Replace with real backend API calls when BACKEND-REG-01 is complete.
 */

export interface Category {
  id: string;
  label: string;
  parentId?: string | null;
  icon?: string;
  displayOrder?: number;
  subcategories?: Category[];
}

let mockCategories: Category[] = [
  {
    id: 'cat-1',
    label: 'Tafsir',
    icon: '📖',
    displayOrder: 1,
    subcategories: [
      { id: 'cat-1-1', label: 'Quran Tafsir', parentId: 'cat-1', displayOrder: 1 },
      { id: 'cat-1-2', label: 'Surah Explanations', parentId: 'cat-1', displayOrder: 2 }
    ]
  },
  {
    id: 'cat-2',
    label: 'Fiqh',
    icon: '⚖️',
    displayOrder: 2,
    subcategories: [
      { id: 'cat-2-1', label: 'Prayer', parentId: 'cat-2', displayOrder: 1 },
      { id: 'cat-2-2', label: 'Fasting', parentId: 'cat-2', displayOrder: 2 },
      { id: 'cat-2-3', label: 'Zakat', parentId: 'cat-2', displayOrder: 3 }
    ]
  },
  {
    id: 'cat-3',
    label: 'Aqeedah',
    icon: '🕌',
    displayOrder: 3,
    subcategories: []
  },
  {
    id: 'cat-4',
    label: 'Seerah',
    icon: '📜',
    displayOrder: 4,
    subcategories: [
      { id: 'cat-4-1', label: 'Prophet Muhammad', parentId: 'cat-4', displayOrder: 1 },
      { id: 'cat-4-2', label: 'Companions', parentId: 'cat-4', displayOrder: 2 }
    ]
  },
  {
    id: 'cat-5',
    label: 'Hadith',
    icon: '📚',
    displayOrder: 5,
    subcategories: []
  }
];

export async function getAllCategories(): Promise<Category[]> {
  await new Promise(resolve => setTimeout(resolve, 500));
  return JSON.parse(JSON.stringify(mockCategories));
}

export async function createCategory(data: {
  name: string;
  parentId?: string | null;
  icon?: string;
  displayOrder?: number;
}): Promise<Category> {
  await new Promise(resolve => setTimeout(resolve, 300));

  const newCategory: Category = {
    id: `cat-${Date.now()}`,
    label: data.name,
    parentId: data.parentId || null,
    icon: data.icon || '',
    displayOrder: data.displayOrder || 0,
    subcategories: []
  };

  if (data.parentId) {
    const parent = findCategoryById(mockCategories, data.parentId);
    if (parent) {
      if (!parent.subcategories) {
        parent.subcategories = [];
      }
      parent.subcategories.push(newCategory);
    }
  } else {
    mockCategories.push(newCategory);
  }

  return newCategory;
}

export async function updateCategory(
  id: string,
  data: {
    name?: string;
    icon?: string;
    displayOrder?: number;
  }
): Promise<Category> {
  await new Promise(resolve => setTimeout(resolve, 300));

  const category = findCategoryById(mockCategories, id);
  if (!category) {
    throw new Error('Category not found');
  }

  if (data.name !== undefined) category.label = data.name;
  if (data.icon !== undefined) category.icon = data.icon;
  if (data.displayOrder !== undefined) category.displayOrder = data.displayOrder;

  return category;
}

export async function deleteCategory(id: string): Promise<void> {
  await new Promise(resolve => setTimeout(resolve, 300));

  function removeCategory(categories: Category[], targetId: string): boolean {
    const index = categories.findIndex(c => c.id === targetId);
    if (index !== -1) {
      categories.splice(index, 1);
      return true;
    }

    for (const cat of categories) {
      if (cat.subcategories && removeCategory(cat.subcategories, targetId)) {
        return true;
      }
    }

    return false;
  }

  const removed = removeCategory(mockCategories, id);
  if (!removed) {
    throw new Error('Category not found');
  }
}

function findCategoryById(categories: Category[], id: string): Category | null {
  for (const cat of categories) {
    if (cat.id === id) return cat;
    if (cat.subcategories) {
      const found = findCategoryById(cat.subcategories, id);
      if (found) return found;
    }
  }
  return null;
}
