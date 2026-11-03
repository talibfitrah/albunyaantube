import { ref, Ref, watch, onBeforeUnmount } from 'vue'

export interface InfiniteScrollOptions {
  threshold?: number        // Distance from bottom to trigger load (default: 200px)
  throttleMs?: number       // Throttle delay in milliseconds (default: 500ms)
  maxEmptyPages?: number    // Empty pages in a row before auto-loading pauses (default: 5)
  onLoadMore: () => void    // Must set isLoading = true before its first await; the composable never does
}

export interface InfiniteScrollReturn {
  containerRef: Ref<HTMLElement | null>
  isLoading: Ref<boolean>
  hasMore: Ref<boolean>
  reset: () => void
  pageLoaded: (itemCount: number, hasNextPage: boolean) => void
  resume: () => void
}

// Pages can come back empty (e.g. a search filtered client-side) while more exist; auto-fill
// would fetch them back to back forever, so by default it pauses after this many in a row.
const MAX_EMPTY_PAGES = 5

/**
 * Composable for infinite scroll functionality with throttling
 *
 * Usage:
 * ```
 * const { containerRef, isLoading, hasMore } = useInfiniteScroll({
 *   threshold: 200,
 *   throttleMs: 500,
 *   onLoadMore: async () => {
 *     if (isLoading.value) return
 *     isLoading.value = true
 *     try { // Load more data
 *     } finally { isLoading.value = false }
 *   }
 * })
 * ```
 */
export function useInfiniteScroll(options: InfiniteScrollOptions): InfiniteScrollReturn {
  const {
    threshold = 200,
    throttleMs = 500,
    maxEmptyPages = MAX_EMPTY_PAGES,
    onLoadMore
  } = options

  const containerRef = ref<HTMLElement | null>(null)
  const isLoading = ref(false)
  const hasMore = ref(true)
  let throttleTimeout: ReturnType<typeof setTimeout> | null = null
  let lastTriggerTime = 0
  let emptyPages = 0

  const checkScroll = () => {
    if (!containerRef.value || isLoading.value || !hasMore.value) {
      return
    }

    const container = containerRef.value
    const { scrollTop, scrollHeight, clientHeight } = container

    // Calculate distance from bottom
    const distanceFromBottom = scrollHeight - (scrollTop + clientHeight)

    // Trigger load more if within threshold
    if (distanceFromBottom <= threshold) {
      const now = Date.now()
      const timeSinceLastTrigger = now - lastTriggerTime

      // Throttle: only trigger if enough time has passed
      if (timeSinceLastTrigger >= throttleMs) {
        lastTriggerTime = now
        // The caller's onLoadMore owns isLoading; pre-setting it here made its guard return early.
        onLoadMore()
      } else {
        // Schedule a delayed trigger
        if (!throttleTimeout) {
          throttleTimeout = setTimeout(() => {
            throttleTimeout = null
            checkScroll()
          }, throttleMs - timeSinceLastTrigger)
        }
      }
    }
  }

  const handleScroll = () => {
    checkScroll()
  }

  // Callers report each page; hasMore turns false at the end or when paused (resume() continues).
  const pageLoaded = (itemCount: number, hasNextPage: boolean) => {
    emptyPages = itemCount ? 0 : emptyPages + 1
    hasMore.value = hasNextPage && emptyPages < maxEmptyPages
  }

  const resume = () => {
    emptyPages = 0
    hasMore.value = true
  }

  const reset = () => {
    isLoading.value = false
    hasMore.value = true
    emptyPages = 0
    lastTriggerTime = 0
    if (throttleTimeout) {
      clearTimeout(throttleTimeout)
      throttleTimeout = null
    }
  }

  // Content that doesn't overflow never fires a scroll event, so re-check when the container
  // attaches and after every load. A macrotask lets the DOM render and the caller's post-load
  // code (e.g. clearing its own initial-load flag) run first.
  const checkFill = () => {
    if (throttleTimeout) return // a pending check runs anyway
    throttleTimeout = setTimeout(() => {
      throttleTimeout = null
      // clientHeight 0 = not laid out (hidden, or jsdom), so fill can't be judged.
      if (containerRef.value?.clientHeight) checkScroll()
    }, 0)
  }

  watch(isLoading, (loading) => { if (!loading) checkFill() })

  // Watch, not onMounted: the container may sit inside a v-if (e.g. a modal mounted closed),
  // so it can appear, disappear and be re-created after mount.
  watch(containerRef, (el, oldEl) => {
    oldEl?.removeEventListener('scroll', handleScroll)
    el?.addEventListener('scroll', handleScroll, { passive: true })
    if (el) checkFill()
  })

  onBeforeUnmount(() => {
    if (containerRef.value) {
      containerRef.value.removeEventListener('scroll', handleScroll)
    }
    if (throttleTimeout) {
      clearTimeout(throttleTimeout)
    }
  })

  return {
    containerRef,
    isLoading,
    hasMore,
    reset,
    pageLoaded,
    resume
  }
}
