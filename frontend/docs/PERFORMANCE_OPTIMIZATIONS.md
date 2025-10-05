# Frontend Performance Optimizations

## Overview
This document outlines all performance optimizations implemented in the frontend application.

## Bundle Optimization

### Code Splitting
- **Route-level splitting**: All views are lazy-loaded using dynamic imports
- **Component-level splitting**: Large components split into separate chunks
- **Vendor chunking**: Third-party libraries grouped into optimized chunks:
  - `vue-core`: Vue, Vue Router, Pinia
  - `firebase`: Firebase SDK
  - `utils`: Axios and utilities
  - `vue-i18n`: Internationalization

### Build Optimizations
- **Target**: ESNext for modern browsers (smaller bundle size)
- **Minification**: Terser with 2-pass compression
- **Tree shaking**: Automatic removal of unused code
- **Console removal**: All console.log removed in production
- **CSS code splitting**: Separate CSS chunks per route

## Runtime Optimizations

### Image Loading
- **LazyImage component**: Intersection Observer-based lazy loading
- **Placeholder support**: Skeleton screens while loading
- **Eager loading option**: For above-the-fold images
- **50px lookahead**: Images start loading before visible

### Event Handling
- **useDebounce**: Debounce search and input events (default: 300ms)
- **useThrottle**: Throttle scroll and resize handlers (default: 300ms)
- **Idle callbacks**: Non-critical tasks run during idle time

### Data Fetching
- **Prefetch on hover**: usePrefetch composable for predictive loading
- **Request deduplication**: Axios interceptors prevent duplicate requests
- **Retry logic**: Automatic retry with exponential backoff
- **Caching**: HTTP cache headers respected

## Performance Monitoring

### Core Web Vitals
- **LCP (Largest Contentful Paint)**: Monitored and logged
- **FID (First Input Delay)**: Tracked for interactivity
- **CLS (Cumulative Layout Shift)**: Measured for visual stability

### Custom Metrics
- **Component mount time**: Track slow components
- **API response time**: Monitor backend performance
- **Bundle size**: Reported during build

## Best Practices

### Component Development
```vue
<script setup lang="ts">
import { defineAsyncComponent } from 'vue';
import { useDebounce } from '@/composables/usePerformance';

// Lazy load heavy components
const HeavyComponent = defineAsyncComponent(
  () => import('./HeavyComponent.vue')
);

// Debounce user input
const debouncedSearch = useDebounce(handleSearch, 500);
</script>
```

### Image Usage
```vue
<template>
  <LazyImage
    src="/path/to/image.jpg"
    alt="Description"
    width="200"
    height="150"
  />
</template>

<script setup lang="ts">
import LazyImage from '@/components/common/LazyImage.vue';
</script>
```

### Performance Tracking
```typescript
import { perfMonitor } from '@/utils/performance';

// Track expensive operations
await perfMonitor.measure('data-processing', async () => {
  return await processLargeDataset();
});

// Get performance summary
const summary = perfMonitor.getSummary();
console.log(`Avg duration: ${summary.avgDuration}ms`);
```

## Bundle Size Targets

| Chunk | Target Size | Current |
|-------|------------|---------|
| Main bundle | < 200 KB | TBD |
| Vue core | < 150 KB | TBD |
| Firebase | < 100 KB | TBD |
| Per-route | < 50 KB | TBD |

## Performance Checklist

- [x] Route-level code splitting
- [x] Component lazy loading
- [x] Image lazy loading
- [x] Event debouncing/throttling
- [x] Bundle size optimization
- [x] Minification and compression
- [x] Core Web Vitals monitoring
- [ ] Service Worker for caching
- [ ] Resource hints (preload, prefetch)
- [ ] Virtual scrolling for long lists

## Measuring Performance

### Development
```bash
# Build and analyze bundle
npm run build

# Run with performance profiling
npm run dev
# Open DevTools > Performance tab
```

### Production
```bash
# Build for production
npm run build

# Preview production build
npm run preview

# Use Lighthouse for audit
```

## Future Optimizations

1. **Virtual scrolling**: For approval queue and content library
2. **Service Worker**: Offline support and aggressive caching
3. **Resource hints**: Preload critical resources
4. **Image optimization**: WebP with fallbacks
5. **Font optimization**: Subset fonts, preload critical fonts
6. **Critical CSS**: Inline above-the-fold CSS

## References

- [Web Vitals](https://web.dev/vitals/)
- [Vite Performance](https://vitejs.dev/guide/performance.html)
- [Vue.js Performance](https://vuejs.org/guide/best-practices/performance.html)
