import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useDebounce, useThrottle, useMemoize } from '@/composables/usePerformance';

describe('usePerformance', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  describe('useDebounce', () => {
    it('should debounce function calls', () => {
      const fn = vi.fn();
      const debouncedFn = useDebounce(fn, 300);

      debouncedFn('arg1');
      debouncedFn('arg2');
      debouncedFn('arg3');

      expect(fn).not.toHaveBeenCalled();

      vi.advanceTimersByTime(300);

      expect(fn).toHaveBeenCalledTimes(1);
      expect(fn).toHaveBeenCalledWith('arg3');
    });

    it('should reset timer on subsequent calls', () => {
      const fn = vi.fn();
      const debouncedFn = useDebounce(fn, 300);

      debouncedFn('arg1');
      vi.advanceTimersByTime(200);
      debouncedFn('arg2');
      vi.advanceTimersByTime(200);
      debouncedFn('arg3');

      expect(fn).not.toHaveBeenCalled();

      vi.advanceTimersByTime(300);

      expect(fn).toHaveBeenCalledTimes(1);
      expect(fn).toHaveBeenCalledWith('arg3');
    });

    it('should use custom delay', () => {
      const fn = vi.fn();
      const debouncedFn = useDebounce(fn, 500);

      debouncedFn();
      vi.advanceTimersByTime(400);
      expect(fn).not.toHaveBeenCalled();

      vi.advanceTimersByTime(100);
      expect(fn).toHaveBeenCalledTimes(1);
    });
  });

  describe('useThrottle', () => {
    it('should throttle function calls', () => {
      const fn = vi.fn();
      const throttledFn = useThrottle(fn, 300);

      throttledFn('arg1');
      expect(fn).toHaveBeenCalledTimes(1);

      throttledFn('arg2');
      throttledFn('arg3');
      expect(fn).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(300);

      throttledFn('arg4');
      expect(fn).toHaveBeenCalledTimes(2);
      expect(fn).toHaveBeenLastCalledWith('arg4');
    });

    it('should allow calls after delay period', () => {
      const fn = vi.fn();
      const throttledFn = useThrottle(fn, 300);

      throttledFn();
      expect(fn).toHaveBeenCalledTimes(1);

      vi.advanceTimersByTime(300);

      throttledFn();
      expect(fn).toHaveBeenCalledTimes(2);
    });
  });

  describe('useMemoize', () => {
    it('should memoize function results', () => {
      const expensiveFn = vi.fn((a: number, b: number) => a + b);
      const memoizedFn = useMemoize(expensiveFn);

      const result1 = memoizedFn(1, 2);
      const result2 = memoizedFn(1, 2);
      const result3 = memoizedFn(1, 2);

      expect(result1).toBe(3);
      expect(result2).toBe(3);
      expect(result3).toBe(3);
      expect(expensiveFn).toHaveBeenCalledTimes(1);
    });

    it('should compute new results for different arguments', () => {
      const expensiveFn = vi.fn((a: number, b: number) => a + b);
      const memoizedFn = useMemoize(expensiveFn);

      const result1 = memoizedFn(1, 2);
      const result2 = memoizedFn(2, 3);
      const result3 = memoizedFn(1, 2);

      expect(result1).toBe(3);
      expect(result2).toBe(5);
      expect(result3).toBe(3);
      expect(expensiveFn).toHaveBeenCalledTimes(2);
    });

    it('should handle complex arguments', () => {
      const fn = vi.fn((obj: { a: number; b: number }) => obj.a + obj.b);
      const memoizedFn = useMemoize(fn);

      memoizedFn({ a: 1, b: 2 });
      memoizedFn({ a: 1, b: 2 });

      expect(fn).toHaveBeenCalledTimes(1);
    });
  });
});
