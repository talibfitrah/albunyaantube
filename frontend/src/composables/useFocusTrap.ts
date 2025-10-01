import { onBeforeUnmount, ref, watch } from 'vue';
import type { Ref } from 'vue';

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

export interface FocusTrapOptions {
  onEscape?: () => void;
  escapeDeactivates?: boolean;
  returnFocus?: boolean;
}

export interface ActivateFocusTrapOptions {
  initialFocus?: HTMLElement | null;
}

export function useFocusTrap(containerRef: Ref<HTMLElement | null>, options: FocusTrapOptions = {}) {
  const escapeDeactivates = options.escapeDeactivates ?? true;
  const shouldReturnFocus = options.returnFocus ?? true;

  const isActive = ref(false);
  const lastFocusedElement = ref<HTMLElement | null>(null);

  function getContainer(): HTMLElement | null {
    return containerRef.value ?? null;
  }

  function getFocusableElements(): HTMLElement[] {
    const container = getContainer();
    if (!container) {
      return [];
    }
    return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
      (element) => !element.hasAttribute('disabled') && element.getAttribute('aria-hidden') !== 'true'
    );
  }

  function focusElement(target: HTMLElement | null | undefined): void {
    if (!target) {
      return;
    }
    if (target !== document.activeElement) {
      target.focus();
    }
  }

  function focusFirstFocusable(opts?: ActivateFocusTrapOptions): void {
    const initial = opts?.initialFocus ?? null;
    if (initial && getContainer()?.contains(initial)) {
      focusElement(initial);
      return;
    }
    const focusable = getFocusableElements();
    if (focusable.length > 0) {
      focusElement(focusable[0]);
      return;
    }
    focusElement(getContainer());
  }

  function restoreFocus(): void {
    if (!shouldReturnFocus) {
      return;
    }
    const target = lastFocusedElement.value;
    if (!target) {
      return;
    }

    if (document.contains(target) && typeof target.focus === 'function') {
      target.focus();
    }
    lastFocusedElement.value = null;
  }

  function handleFocusIn(event: FocusEvent): void {
    if (!isActive.value) {
      return;
    }
    const container = getContainer();
    const target = event.target as HTMLElement | null;
    if (!container || !target) {
      return;
    }
    if (!container.contains(target)) {
      focusFirstFocusable();
    }
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (!isActive.value) {
      return;
    }

    if (event.key === 'Escape' && escapeDeactivates) {
      event.preventDefault();
      options.onEscape?.();
      return;
    }

    if (event.key !== 'Tab') {
      return;
    }

    const focusable = getFocusableElements();
    if (focusable.length === 0) {
      event.preventDefault();
      focusElement(getContainer());
      return;
    }

    const activeElement = document.activeElement as HTMLElement | null;
    const currentIndex = activeElement ? focusable.indexOf(activeElement) : -1;

    event.preventDefault();

    if (event.shiftKey) {
      if (currentIndex <= 0) {
        focusElement(focusable[focusable.length - 1]);
        return;
      }
      focusElement(focusable[currentIndex - 1]);
      return;
    }

    if (currentIndex === -1 || currentIndex === focusable.length - 1) {
      focusElement(focusable[0]);
      return;
    }

    focusElement(focusable[currentIndex + 1]);
  }

  function attachListeners(): void {
    const container = getContainer();
    if (!container) {
      return;
    }
    container.addEventListener('keydown', handleKeydown);
    document.addEventListener('focusin', handleFocusIn, true);
  }

  function detachListeners(): void {
    const container = getContainer();
    if (container) {
      container.removeEventListener('keydown', handleKeydown);
    }
    document.removeEventListener('focusin', handleFocusIn, true);
  }

  function activate(opts?: ActivateFocusTrapOptions): void {
    if (isActive.value) {
      focusFirstFocusable(opts);
      return;
    }
    lastFocusedElement.value = document.activeElement as HTMLElement | null;
    attachListeners();
    isActive.value = true;
    focusFirstFocusable(opts);
  }

  function deactivate(): void {
    if (!isActive.value) {
      return;
    }
    detachListeners();
    isActive.value = false;
    restoreFocus();
  }

  onBeforeUnmount(() => {
    detachListeners();
  });

  watch(
    () => containerRef.value,
    (_next, previous) => {
      if (previous) {
        previous.removeEventListener('keydown', handleKeydown);
      }
      if (isActive.value) {
        attachListeners();
      }
    }
  );

  return {
    activate,
    deactivate,
    isActive
  };
}
