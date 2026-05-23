import { ref, type Ref } from 'vue'
import { getAllCategories } from '@/services/categoryService'

interface FlatCategory { id: string; name: string }

// Module-level cache so the API call doesn't happen twice when default picker + row editor are both mounted.
let cachedPromise: Promise<FlatCategory[]> | null = null

function flatten(nodes: any[]): FlatCategory[] {
  const out: FlatCategory[] = []
  const walk = (ns: any[]) => {
    for (const n of ns) {
      out.push({ id: n.id, name: n.name })
      if (n.children?.length) walk(n.children)
      if (n.subcategories?.length) walk(n.subcategories)
    }
  }
  walk(nodes)
  return out
}

export function useCategoryPicker() {
  const flatCategories: Ref<FlatCategory[]> = ref([])
  const isLoading = ref(true)
  const loadError = ref<string | null>(null)

  async function load() {
    try {
      if (!cachedPromise) {
        cachedPromise = getAllCategories().then(flatten)
      }
      flatCategories.value = await cachedPromise
    } catch (e) {
      loadError.value = (e as Error).message
      cachedPromise = null
    } finally {
      isLoading.value = false
    }
  }

  return { flatCategories, isLoading, loadError, load }
}
