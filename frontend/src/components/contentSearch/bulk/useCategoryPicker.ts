import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { getAllCategories } from '@/services/categoryService'
import { useAuthStore } from '@/stores/auth'

interface FlatCategory { id: string; name: string; type?: string | null }

// Module-level cache so the API call doesn't happen twice when default picker + row editor are both mounted.
// NOTE: invalidate if the admin edits the category tree elsewhere in the session (separate ticket).
let cachedPromise: Promise<FlatCategory[]> | null = null

/** Return the next selection after toggling `id`'s membership in `current`. */
export function toggleCategoryId(current: readonly string[], id: string): string[] {
  return current.includes(id)
    ? current.filter((x) => x !== id)
    : [...current, id]
}

/** Display label for a category — name preferred, id as fallback. */
export function categoryDisplayName(cat: { id: string; name: string }): string {
  return cat.name || cat.id
}

function flatten(nodes: any[]): FlatCategory[] {
  const out: FlatCategory[] = []
  const walk = (ns: any[]) => {
    for (const n of ns) {
      out.push({ id: n.id, name: n.name, type: n.type })
      if (n.children?.length) walk(n.children)
      if (n.subcategories?.length) walk(n.subcategories)
    }
  }
  walk(nodes)
  return out
}

/** `playlist`: the picker is for a playlist row, which the live category refuses. Moderators never see LIVE (R1). */
export function useCategoryPicker(opts: { playlist?: boolean } = {}) {
  const authStore = useAuthStore()
  const all: Ref<FlatCategory[]> = ref([])
  const flatCategories: ComputedRef<FlatCategory[]> = computed(() =>
    authStore.isAdmin && !opts.playlist ? all.value : all.value.filter((c) => c.type !== 'LIVE'))
  const isLoading = ref(true)
  const loadError = ref<string | null>(null)

  async function load() {
    try {
      if (!cachedPromise) {
        cachedPromise = getAllCategories().then(flatten)
      }
      all.value = await cachedPromise
    } catch (e) {
      loadError.value = (e as Error).message
      cachedPromise = null
    } finally {
      isLoading.value = false
    }
  }

  return { flatCategories, isLoading, loadError, load }
}
