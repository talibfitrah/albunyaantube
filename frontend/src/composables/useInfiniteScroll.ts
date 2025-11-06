import { ref, Ref, onMounted, onBeforeUnmount } from 'vue'

export interface InfiniteScrollOptions {
  threshold?: number        // Distance from bottom to trigger load (default: 200px)
  throttleMs?: number       // Throttle delay in milliseconds (default: 500ms)
  onLoadMore: () => void    // Callback to load more items
}

export interface InfiniteScrollReturn {
  containerRef: Ref<HTMLElement | null>
  isLoading: Ref<boolean>
  hasMore: Ref<boolean>
  reset: () => void
}

/**
 * Composable for infinite scroll functionality with throttling
 *
 * Usage:
 * ```
 * const { containerRef, isLoading, hasMore } = useInfiniteScroll({
 *   threshold: 200,
 *   throttleMs: 500,
 *   onLoadMore: async () => {
 *     // Load more data
 *   }
 * })
 * ```
 */
export function useInfiniteScroll(options: InfiniteScrollOptions): InfiniteScrollReturn {
  const {
    threshold = 200,
    throttleMs = 500,
    onLoadMore
  } = options

  const containerRef = ref<HTMLElement | null>(null)
  const isLoading = ref(false)
  const hasMore = ref(true)
  let throttleTimeout: ReturnType<typeof setTimeout> | null = null
  let lastTriggerTime = 0

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
        isLoading.value = true

        // Execute the callback
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

  const reset = () => {
    isLoading.value = false
    hasMore.value = true
    lastTriggerTime = 0
    if (throttleTimeout) {
      clearTimeout(throttleTimeout)
      throttleTimeout = null
    }
  }

  onMounted(() => {
    if (containerRef.value) {
      containerRef.value.addEventListener('scroll', handleScroll, { passive: true })
    }
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
    reset
  }
}
