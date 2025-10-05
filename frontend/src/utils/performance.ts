/**
 * Performance monitoring utilities
 * Track and report performance metrics
 */

interface PerformanceMetrics {
  name: string;
  duration: number;
  timestamp: number;
}

class PerformanceMonitor {
  private metrics: PerformanceMetrics[] = [];
  private timers: Map<string, number> = new Map();

  /**
   * Start timing an operation
   */
  start(name: string): void {
    this.timers.set(name, performance.now());
  }

  /**
   * End timing and record metric
   */
  end(name: string): number | null {
    const startTime = this.timers.get(name);
    if (!startTime) {
      console.warn(`[Performance] No start time found for: ${name}`);
      return null;
    }

    const duration = performance.now() - startTime;
    this.timers.delete(name);

    this.metrics.push({
      name,
      duration,
      timestamp: Date.now()
    });

    // Log slow operations (> 1000ms)
    if (duration > 1000) {
      console.warn(`[Performance] Slow operation detected: ${name} took ${duration.toFixed(2)}ms`);
    }

    return duration;
  }

  /**
   * Measure a function's execution time
   */
  async measure<T>(name: string, fn: () => T | Promise<T>): Promise<T> {
    this.start(name);
    try {
      const result = await fn();
      this.end(name);
      return result;
    } catch (error) {
      this.end(name);
      throw error;
    }
  }

  /**
   * Get all recorded metrics
   */
  getMetrics(): PerformanceMetrics[] {
    return [...this.metrics];
  }

  /**
   * Get metrics by name pattern
   */
  getMetricsByPattern(pattern: RegExp): PerformanceMetrics[] {
    return this.metrics.filter(m => pattern.test(m.name));
  }

  /**
   * Clear all metrics
   */
  clear(): void {
    this.metrics = [];
    this.timers.clear();
  }

  /**
   * Get performance summary
   */
  getSummary(): {
    totalMetrics: number;
    avgDuration: number;
    maxDuration: number;
    slowestOperation: string | null;
  } {
    if (this.metrics.length === 0) {
      return {
        totalMetrics: 0,
        avgDuration: 0,
        maxDuration: 0,
        slowestOperation: null
      };
    }

    const durations = this.metrics.map(m => m.duration);
    const maxDuration = Math.max(...durations);
    const slowest = this.metrics.find(m => m.duration === maxDuration);

    return {
      totalMetrics: this.metrics.length,
      avgDuration: durations.reduce((a, b) => a + b, 0) / durations.length,
      maxDuration,
      slowestOperation: slowest?.name || null
    };
  }

  /**
   * Report Core Web Vitals
   */
  reportWebVitals(): void {
    if (!('PerformanceObserver' in window)) {
      console.warn('[Performance] PerformanceObserver not supported');
      return;
    }

    // Largest Contentful Paint (LCP)
    try {
      const lcpObserver = new PerformanceObserver((list) => {
        const entries = list.getEntries();
        const lastEntry = entries[entries.length - 1] as any;
        console.log(`[Performance] LCP: ${lastEntry.renderTime || lastEntry.loadTime}ms`);
      });
      lcpObserver.observe({ entryTypes: ['largest-contentful-paint'] });
    } catch (e) {
      // LCP not supported
    }

    // First Input Delay (FID)
    try {
      const fidObserver = new PerformanceObserver((list) => {
        list.getEntries().forEach((entry: any) => {
          const delay = entry.processingStart - entry.startTime;
          console.log(`[Performance] FID: ${delay}ms`);
        });
      });
      fidObserver.observe({ entryTypes: ['first-input'] });
    } catch (e) {
      // FID not supported
    }

    // Cumulative Layout Shift (CLS)
    try {
      let clsValue = 0;
      const clsObserver = new PerformanceObserver((list) => {
        list.getEntries().forEach((entry: any) => {
          if (!entry.hadRecentInput) {
            clsValue += entry.value;
          }
        });
        console.log(`[Performance] CLS: ${clsValue}`);
      });
      clsObserver.observe({ entryTypes: ['layout-shift'] });
    } catch (e) {
      // CLS not supported
    }
  }
}

// Export singleton instance
export const perfMonitor = new PerformanceMonitor();

// Helper function for component performance tracking
export function trackComponentPerformance(componentName: string) {
  return {
    onBeforeMount: () => perfMonitor.start(`${componentName}-mount`),
    onMounted: () => perfMonitor.end(`${componentName}-mount`),
    onBeforeUpdate: () => perfMonitor.start(`${componentName}-update`),
    onUpdated: () => perfMonitor.end(`${componentName}-update`)
  };
}

// Report web vitals in production
if (import.meta.env.PROD) {
  perfMonitor.reportWebVitals();
}
