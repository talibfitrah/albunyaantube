import { render } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { nextTick } from 'vue';
import { describe, it, expect } from 'vitest';
import ToastContainer from '@/components/common/ToastContainer.vue';
import { messages } from '@/locales/messages';
import { useToast } from '@/composables/useToast';

describe('ToastContainer', () => {
  it('keeps the line breaks of a multi-line message (a LIVE refusal lists one offender per line)', async () => {
    const { unmount } = render(ToastContainer, {
      global: { plugins: [createI18n({ legacy: false, locale: 'en', messages })] }
    });
    const toast = useToast();
    const id = toast.error('Refused\nMy playlist: not a live stream', 0);
    await nextTick();

    const message = document.querySelector('.toast-message') as HTMLElement;
    expect(message.textContent).toBe('Refused\nMy playlist: not a live stream');
    expect(getComputedStyle(message).whiteSpace).toBe('pre-line');

    toast.remove(id);
    unmount();
  });
});
