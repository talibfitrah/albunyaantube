import { useToast } from '@/composables/useToast';

export type ToastType = 'success' | 'error' | 'info' | 'warning';

export interface ToastOptions {
  duration?: number;
  position?: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left';
}

const DEFAULT_DURATION = 3000;

function showToast(message: string, type: ToastType, options: ToastOptions = {}) {
  const { duration = DEFAULT_DURATION } = options;
  const logMap = {
    success: console.log,
    error: console.error,
    info: console.info,
    warning: console.warn
  };
  logMap[type](`[${type.toUpperCase()}]`, message);

  useToast().show(type, message, duration);
}

export const toast = {
  success: (message: string, options?: ToastOptions) => {
    showToast(message, 'success', options);
  },

  error: (message: string, options?: ToastOptions) => {
    showToast(message, 'error', options);
  },

  info: (message: string, options?: ToastOptions) => {
    showToast(message, 'info', options);
  },

  warning: (message: string, options?: ToastOptions) => {
    showToast(message, 'warning', options);
  }
};
