import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { describe, expect, it, vi } from 'vitest'
import BulkRowCategoryEditor from '../src/components/contentSearch/bulk/BulkRowCategoryEditor.vue'
import BulkDefaultCategoriesPicker from '../src/components/contentSearch/bulk/BulkDefaultCategoriesPicker.vue'
import { messages } from '../src/locales/messages'

const auth = vi.hoisted(() => ({ isAdmin: true }))
vi.mock('../src/stores/auth', () => ({ useAuthStore: () => auth }))
vi.mock('../src/services/categoryService', () => ({
  getAllCategories: () => Promise.resolve([
    { id: 'live', name: 'Channels Live', type: 'LIVE', subcategories: [] },
    { id: 'quran', name: 'Quran', type: null, subcategories: [] },
  ]),
}))

const plugins = [createI18n({ legacy: false, locale: 'en', messages })]
const chips = async (comp: unknown, props: Record<string, unknown>) => {
  const w = mount(comp as never, { props, global: { plugins } })
  await flushPromises()
  return w.findAll('button[type=button]').map((b) => b.text())
}

describe('bulk category pickers and the live category (F-M2)', () => {
  it('offer LIVE to an admin on a non-playlist row', async () => {
    auth.isAdmin = true
    expect(await chips(BulkRowCategoryEditor, { modelValue: [] })).toContain('Channels Live')
  })

  it('drop LIVE for a playlist row', async () => {
    auth.isAdmin = true
    expect(await chips(BulkRowCategoryEditor, { modelValue: [], playlist: true })).not.toContain('Channels Live')
  })

  it('drop LIVE for a moderator, in the row editor and the defaults picker', async () => {
    auth.isAdmin = false
    expect(await chips(BulkRowCategoryEditor, { modelValue: [] })).not.toContain('Channels Live')
    expect(await chips(BulkDefaultCategoriesPicker, { modelValue: [] })).toEqual(['Quran'])
  })
})
