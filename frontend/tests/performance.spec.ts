import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { perfMonitor } from '@/utils/performance';

describe('Performance Monitor', () => {
  beforeEach(() => {
    perfMonitor.clear();
  });

  afterEach(() => {
    perfMonitor.clear();
  });

  describe('start and end', () => {
    it('should track operation duration', () => {
      perfMonitor.start('test-operation');

      // Simulate some work
      const start = performance.now();
      while (performance.now() - start < 10) {
        // Wait 10ms
      }

      const duration = perfMonitor.end('test-operation');

      expect(duration).toBeGreaterThan(0);
      expect(duration).toBeGreaterThanOrEqual(10);
    });

    it('should return null if start was never called', () => {
      const duration = perfMonitor.end('non-existent');
      expect(duration).toBeNull();
    });

    it('should delete timer after end', () => {
      perfMonitor.start('test');
      perfMonitor.end('test');

      const duration2 = perfMonitor.end('test');
      expect(duration2).toBeNull();
    });
  });

  describe('measure', () => {
    it('should measure synchronous function', async () => {
      const fn = () => {
        let sum = 0;
        for (let i = 0; i < 1000; i++) {
          sum += i;
        }
        return sum;
      };

      const result = await perfMonitor.measure('sync-test', fn);

      expect(result).toBe(499500);
      const metrics = perfMonitor.getMetrics();
      expect(metrics).toHaveLength(1);
      expect(metrics[0].name).toBe('sync-test');
    });

    it('should measure async function', async () => {
      const fn = async () => {
        await new Promise(resolve => setTimeout(resolve, 50));
        return 'done';
      };

      const result = await perfMonitor.measure('async-test', fn);

      expect(result).toBe('done');
      const metrics = perfMonitor.getMetrics();
      expect(metrics).toHaveLength(1);
      expect(metrics[0].duration).toBeGreaterThan(0);
    });

    it('should handle function errors', async () => {
      const fn = () => {
        throw new Error('Test error');
      };

      await expect(perfMonitor.measure('error-test', fn)).rejects.toThrow('Test error');

      const metrics = perfMonitor.getMetrics();
      expect(metrics).toHaveLength(1);
    });
  });

  describe('getMetrics', () => {
    it('should return all recorded metrics', () => {
      perfMonitor.start('op1');
      perfMonitor.end('op1');
      perfMonitor.start('op2');
      perfMonitor.end('op2');

      const metrics = perfMonitor.getMetrics();
      expect(metrics).toHaveLength(2);
    });

    it('should return copy of metrics array', () => {
      perfMonitor.start('test');
      perfMonitor.end('test');

      const metrics1 = perfMonitor.getMetrics();
      const metrics2 = perfMonitor.getMetrics();

      expect(metrics1).not.toBe(metrics2);
      expect(metrics1).toEqual(metrics2);
    });
  });

  describe('getMetricsByPattern', () => {
    it('should filter metrics by pattern', () => {
      perfMonitor.start('api-fetch-users');
      perfMonitor.end('api-fetch-users');
      perfMonitor.start('api-fetch-categories');
      perfMonitor.end('api-fetch-categories');
      perfMonitor.start('render-component');
      perfMonitor.end('render-component');

      const apiMetrics = perfMonitor.getMetricsByPattern(/^api-/);
      expect(apiMetrics).toHaveLength(2);
    });
  });

  describe('getSummary', () => {
    it('should return empty summary for no metrics', () => {
      const summary = perfMonitor.getSummary();

      expect(summary).toEqual({
        totalMetrics: 0,
        avgDuration: 0,
        maxDuration: 0,
        slowestOperation: null
      });
    });

    it('should calculate correct summary', () => {
      perfMonitor.start('fast');
      perfMonitor.end('fast'); // ~0ms

      const start = performance.now();
      while (performance.now() - start < 20) {} // Wait 20ms

      perfMonitor.start('slow');
      const slowStart = performance.now();
      while (performance.now() - slowStart < 20) {} // Wait 20ms
      perfMonitor.end('slow');

      const summary = perfMonitor.getSummary();

      expect(summary.totalMetrics).toBe(2);
      expect(summary.avgDuration).toBeGreaterThan(0);
      expect(summary.maxDuration).toBeGreaterThan(0);
      expect(summary.slowestOperation).toBe('slow');
    });
  });

  describe('clear', () => {
    it('should clear all metrics and timers', () => {
      perfMonitor.start('test1');
      perfMonitor.end('test1');
      perfMonitor.start('test2');

      perfMonitor.clear();

      expect(perfMonitor.getMetrics()).toHaveLength(0);
      expect(perfMonitor.end('test2')).toBeNull();
    });
  });
});
