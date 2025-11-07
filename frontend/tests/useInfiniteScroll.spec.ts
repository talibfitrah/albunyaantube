import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h, ref } from 'vue';
import { useInfiniteScroll } from '@/composables/useInfiniteScroll';

describe('useInfiniteScroll', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  const createTestComponent = (options: {
    threshold?: number;
    throttleMs?: number;
    onLoadMore: () => void;
  }) => {
    return defineComponent({
      setup() {
        const scrollable = useInfiniteScroll(options);
        return { ...scrollable };
      },
      render() {
        return h('div', {
          ref: 'containerRef',
          style: { height: '500px', overflow: 'auto' }
        }, [
          h('div', { style: { height: '2000px' } }, 'Content')
        ]);
      }
    });
  };

  it('should initialize with default values', () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({ onLoadMore }));

    expect(wrapper.vm.isLoading).toBe(false);
    expect(wrapper.vm.hasMore).toBe(true);
    expect(wrapper.vm.containerRef).toBeDefined();
  });

  it('should trigger onLoadMore when scrolled to threshold', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({
      threshold: 200,
      throttleMs: 500,
      onLoadMore
    }));

    await wrapper.vm.$nextTick();

    // Mock scrolling near bottom
    const container = wrapper.vm.containerRef as HTMLElement;
    if (container) {
      Object.defineProperty(container, 'scrollTop', { value: 1400, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 2000, writable: true });
      Object.defineProperty(container, 'clientHeight', { value: 500, writable: true });

      // Trigger scroll event
      container.dispatchEvent(new Event('scroll'));

      await wrapper.vm.$nextTick();

      expect(wrapper.vm.isLoading).toBe(true);
      expect(onLoadMore).toHaveBeenCalledTimes(1);
    }
  });

  it('should not trigger onLoadMore if already loading', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({ onLoadMore }));

    await wrapper.vm.$nextTick();

    // Set loading state
    wrapper.vm.isLoading = true;

    const container = wrapper.vm.containerRef as HTMLElement;
    if (container) {
      Object.defineProperty(container, 'scrollTop', { value: 1400, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 2000, writable: true });
      Object.defineProperty(container, 'clientHeight', { value: 500, writable: true });

      container.dispatchEvent(new Event('scroll'));

      await wrapper.vm.$nextTick();

      expect(onLoadMore).not.toHaveBeenCalled();
    }
  });

  it('should not trigger onLoadMore if hasMore is false', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({ onLoadMore }));

    await wrapper.vm.$nextTick();

    // Set hasMore to false
    wrapper.vm.hasMore = false;

    const container = wrapper.vm.containerRef as HTMLElement;
    if (container) {
      Object.defineProperty(container, 'scrollTop', { value: 1400, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 2000, writable: true });
      Object.defineProperty(container, 'clientHeight', { value: 500, writable: true });

      container.dispatchEvent(new Event('scroll'));

      await wrapper.vm.$nextTick();

      expect(onLoadMore).not.toHaveBeenCalled();
    }
  });

  it('should throttle scroll events', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({
      threshold: 200,
      throttleMs: 500,
      onLoadMore
    }));

    await wrapper.vm.$nextTick();

    const container = wrapper.vm.containerRef as HTMLElement;
    if (container) {
      Object.defineProperty(container, 'scrollTop', { value: 1400, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 2000, writable: true });
      Object.defineProperty(container, 'clientHeight', { value: 500, writable: true });

      // Trigger multiple scroll events
      container.dispatchEvent(new Event('scroll'));
      wrapper.vm.isLoading = false; // Reset loading
      container.dispatchEvent(new Event('scroll'));
      wrapper.vm.isLoading = false; // Reset loading
      container.dispatchEvent(new Event('scroll'));

      await wrapper.vm.$nextTick();

      // Should only call once due to throttling
      expect(onLoadMore).toHaveBeenCalledTimes(1);

      // Advance time to allow next call
      vi.advanceTimersByTime(500);
      wrapper.vm.isLoading = false; // Reset loading
      container.dispatchEvent(new Event('scroll'));

      await wrapper.vm.$nextTick();

      expect(onLoadMore).toHaveBeenCalledTimes(2);
    }
  });

  it('should use custom threshold', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({
      threshold: 100,
      onLoadMore
    }));

    await wrapper.vm.$nextTick();

    const container = wrapper.vm.containerRef as HTMLElement;
    if (container) {
      // Within 100px threshold (2000 - (1450 + 500) = 50px from bottom)
      Object.defineProperty(container, 'scrollTop', { value: 1450, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 2000, writable: true });
      Object.defineProperty(container, 'clientHeight', { value: 500, writable: true });

      container.dispatchEvent(new Event('scroll'));

      await wrapper.vm.$nextTick();

      expect(onLoadMore).toHaveBeenCalledTimes(1);
    }
  });

  it('should reset state when reset() is called', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({ onLoadMore }));

    await wrapper.vm.$nextTick();

    // Set some state
    wrapper.vm.isLoading = true;
    wrapper.vm.hasMore = false;

    // Call reset
    wrapper.vm.reset();

    expect(wrapper.vm.isLoading).toBe(false);
    expect(wrapper.vm.hasMore).toBe(true);
  });

  it('should cleanup event listeners on unmount', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({ onLoadMore }));

    await wrapper.vm.$nextTick();

    const container = wrapper.vm.containerRef as HTMLElement;
    const removeEventListenerSpy = vi.spyOn(container, 'removeEventListener');

    wrapper.unmount();

    expect(removeEventListenerSpy).toHaveBeenCalledWith('scroll', expect.any(Function));
  });

  it('should not trigger when scrolled far from bottom', async () => {
    const onLoadMore = vi.fn();
    const wrapper = mount(createTestComponent({
      threshold: 200,
      onLoadMore
    }));

    await wrapper.vm.$nextTick();

    const container = wrapper.vm.containerRef as HTMLElement;
    if (container) {
      // Far from bottom (2000 - (300 + 500) = 1200px from bottom)
      Object.defineProperty(container, 'scrollTop', { value: 300, writable: true });
      Object.defineProperty(container, 'scrollHeight', { value: 2000, writable: true });
      Object.defineProperty(container, 'clientHeight', { value: 500, writable: true });

      container.dispatchEvent(new Event('scroll'));

      await wrapper.vm.$nextTick();

      expect(onLoadMore).not.toHaveBeenCalled();
    }
  });
});
