import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getAllCategories, createCategory, updateCategory, deleteCategory } from '@/services/categoryService';
import apiClient from '@/services/api/client';

vi.mock('@/services/api/client');

describe('CategoryService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('getAllCategories', () => {
    it('should fetch and build hierarchical category tree', async () => {
      const mockCategories = [
        {
          id: 'cat-1',
          name: 'Parent',
          parentCategoryId: null,
          displayOrder: 1
        },
        {
          id: 'cat-2',
          name: 'Child',
          parentCategoryId: 'cat-1',
          displayOrder: 1
        }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockCategories });

      const result = await getAllCategories();

      expect(result).toHaveLength(1);
      expect(result[0].label).toBe('Parent');
      expect(result[0].subcategories).toHaveLength(1);
      expect(result[0].subcategories![0].label).toBe('Child');
    });

    it('should normalize field names (name -> label)', async () => {
      const mockCategories = [
        {
          id: 'cat-1',
          name: 'Test Category',
          parentCategoryId: null
        }
      ];

      vi.mocked(apiClient.get).mockResolvedValueOnce({ data: mockCategories });

      const result = await getAllCategories();

      expect(result[0].label).toBe('Test Category');
    });
  });

  describe('createCategory', () => {
    it('should create a new category', async () => {
      const newCategory = {
        name: 'New Category',
        parentId: null,
        icon: '📚',
        displayOrder: 1
      };

      const mockResponse = {
        id: 'cat-new',
        name: 'New Category',
        parentCategoryId: null,
        icon: '📚',
        displayOrder: 1
      };

      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: mockResponse });

      const result = await createCategory(newCategory);

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/categories', {
        name: 'New Category',
        parentCategoryId: null,
        icon: '📚',
        displayOrder: 1
      });
      expect(result.id).toBe('cat-new');
    });

    it('should create subcategory with parentId', async () => {
      const newCategory = {
        name: 'Subcategory',
        parentId: 'parent-1',
        icon: '',
        displayOrder: 0
      };

      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {} });

      await createCategory(newCategory);

      expect(apiClient.post).toHaveBeenCalledWith('/api/admin/categories', {
        name: 'Subcategory',
        parentCategoryId: 'parent-1',
        icon: '',
        displayOrder: 0
      });
    });

    it('should omit displayOrder from payload when null (auto-assign)', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: { id: 'cat-auto' } });

      await createCategory({
        name: 'Auto Ordered',
        parentId: null,
        icon: '',
        displayOrder: null
      });

      const payload = vi.mocked(apiClient.post).mock.calls[0][1] as Record<string, any>;
      expect(payload).not.toHaveProperty('displayOrder');
      expect(payload).toEqual({
        name: 'Auto Ordered',
        parentCategoryId: null,
        icon: ''
      });
    });

    it('should omit displayOrder from payload when undefined (auto-assign)', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: { id: 'cat-auto2' } });

      await createCategory({
        name: 'Auto Ordered 2',
        icon: ''
      });

      const payload = vi.mocked(apiClient.post).mock.calls[0][1] as Record<string, any>;
      expect(payload).not.toHaveProperty('displayOrder');
    });

    it('should include displayOrder: 0 in payload when explicitly set', async () => {
      vi.mocked(apiClient.post).mockResolvedValueOnce({ data: { id: 'cat-zero' } });

      await createCategory({
        name: 'First Position',
        displayOrder: 0
      });

      const payload = vi.mocked(apiClient.post).mock.calls[0][1] as Record<string, any>;
      expect(payload.displayOrder).toBe(0);
    });
  });

  describe('updateCategory', () => {
    it('should update category fields', async () => {
      const updates = {
        name: 'Updated Name',
        icon: '🔖',
        displayOrder: 5
      };

      vi.mocked(apiClient.put).mockResolvedValueOnce({ data: {} });

      await updateCategory('cat-1', updates);

      expect(apiClient.put).toHaveBeenCalledWith('/api/admin/categories/cat-1', updates);
    });
  });

  describe('deleteCategory', () => {
    it('should delete a category', async () => {
      vi.mocked(apiClient.delete).mockResolvedValueOnce({ data: {} });

      await deleteCategory('cat-1');

      expect(apiClient.delete).toHaveBeenCalledWith('/api/admin/categories/cat-1');
    });

    it('should handle conflict error for categories with subcategories', async () => {
      const error = {
        response: { status: 409 }
      };

      vi.mocked(apiClient.delete).mockRejectedValueOnce(error);

      await expect(deleteCategory('cat-1')).rejects.toThrow('Category has subcategories');
    });
  });
});
