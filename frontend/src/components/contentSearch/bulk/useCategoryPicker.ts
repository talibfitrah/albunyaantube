import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { getAllCategories } from '@/services/categoryService'
import { useAuthStore } from '@/stores/auth'

interface FlatCategory { id: string; name: string; type?: string | null }

// Shares one in-flight request between the default picker and row editor mounting together. Dropped as soon as it
// settles, so a category type changed elsewhere in the session is never read stale.
let inflight: Promise<FlatCategory[]> | null = null
const fetchFlat = () => (inflight ??= getAllCategories().then(flatten).finally(() => { inflight = null }))

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

/** Ids of the LIVE categories (the server refuses them on playlists). */
export async function liveCategoryIds(): Promise<Set<string>> {
  return new Set((await fetchFlat()).filter((c) => c.type === 'LIVE').map((c) => c.id))
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
      all.value = await fetchFlat()
    } catch (e) {
      loadError.value = (e as Error).message
    } finally {
      isLoading.value = false
    }
  }

  return { flatCategories, isLoading, loadError, load }
}
