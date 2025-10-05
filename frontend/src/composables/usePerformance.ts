/**
 * Performance optimization composables
 * Provides debounce, throttle, and lazy loading utilities
 */

import { ref, onMounted, onBeforeUnmount, type Ref } from 'vue';

/**
 * Debounce a function
 */
export function useDebounce<T extends (...args: any[]) => any>(
  fn: T,
  delay: number = 300
): (...args: Parameters<T>) => void {
  let timeoutId: ReturnType<typeof setTimeout> | null = null;

  return (...args: Parameters<T>) => {
    if (timeoutId) {
      clearTimeout(timeoutId);
    }
    timeoutId = setTimeout(() => {
      fn(...args);
    }, delay);
  };
}

/**
 * Throttle a function
 */
export function useThrottle<T extends (...args: any[]) => any>(
  fn: T,
  delay: number = 300
): (...args: Parameters<T>) => void {
  let lastCall = 0;

  return (...args: Parameters<T>) => {
    const now = Date.now();
    if (now - lastCall >= delay) {
      lastCall = now;
      fn(...args);
    }
  };
}

/**
 * Lazy load elements using Intersection Observer
 */
export function useLazyLoad(
  target: Ref<HTMLElement | null>,
  callback: () => void,
  options: IntersectionObserverInit = {}
) {
  const isVisible = ref(false);
  let observer: IntersectionObserver | null = null;

  onMounted(() => {
    if (!target.value) return;

    observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            isVisible.value = true;
            callback();
            if (observer && target.value) {
              observer.unobserve(target.value);
            }
          }
        });
      },
      {
        rootMargin: '50px',
        ...options
      }
    );

    observer.observe(target.value);
  });

  onBeforeUnmount(() => {
    if (observer && target.value) {
      observer.unobserve(target.value);
    }
  });

  return { isVisible };
}

/**
 * Request idle callback wrapper
 */
export function useIdleCallback(callback: () => void, options?: IdleRequestOptions) {
  onMounted(() => {
    if ('requestIdleCallback' in window) {
      requestIdleCallback(callback, options);
    } else {
      // Fallback for browsers that don't support requestIdleCallback
      setTimeout(callback, 1);
    }
  });
}

/**
 * Prefetch data on hover
 */
export function usePrefetch<T>(
  fetchFn: () => Promise<T>,
  delay: number = 200
): {
  onMouseEnter: () => void;
  data: Ref<T | null>;
  isLoading: Ref<boolean>;
} {
  const data = ref<T | null>(null) as Ref<T | null>;
  const isLoading = ref(false);
  let timeoutId: ReturnType<typeof setTimeout> | null = null;
  let hasFetched = false;

  const onMouseEnter = () => {
    if (hasFetched || isLoading.value) return;

    timeoutId = setTimeout(async () => {
      isLoading.value = true;
      try {
        data.value = await fetchFn();
        hasFetched = true;
      } catch (error) {
        console.error('Prefetch error:', error);
      } finally {
        isLoading.value = false;
      }
    }, delay);
  };

  onBeforeUnmount(() => {
    if (timeoutId) {
      clearTimeout(timeoutId);
    }
  });

  return {
    onMouseEnter,
    data,
    isLoading
  };
}

/**
 * Memoize expensive computations
 */
export function useMemoize<T extends (...args: any[]) => any>(fn: T): T {
  const cache = new Map<string, ReturnType<T>>();

  return ((...args: Parameters<T>) => {
    const key = JSON.stringify(args);
    if (cache.has(key)) {
      return cache.get(key)!;
    }
    const result = fn(...args);
    cache.set(key, result);
    return result;
  }) as T;
}
