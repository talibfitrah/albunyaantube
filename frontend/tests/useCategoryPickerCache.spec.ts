import { beforeEach, describe, expect, it, vi } from 'vitest'

const getAll = vi.hoisted(() => vi.fn())
vi.mock('../src/services/categoryService', () => ({ getAllCategories: getAll }))
vi.mock('../src/stores/auth', () => ({ useAuthStore: () => ({ isAdmin: true }) }))

import { liveCategoryIds } from '../src/components/contentSearch/bulk/useCategoryPicker'

describe('liveCategoryIds', () => {
  beforeEach(() => getAll.mockReset())

  it('sees a category type changed after the first read', async () => {
    getAll.mockResolvedValueOnce([{ id: 'c1', name: 'C', type: 'LIVE', subcategories: [] }])
    expect([...(await liveCategoryIds())]).toEqual(['c1'])
    getAll.mockResolvedValueOnce([{ id: 'c1', name: 'C', type: null, subcategories: [] }])
    expect([...(await liveCategoryIds())]).toEqual([])
  })
})
