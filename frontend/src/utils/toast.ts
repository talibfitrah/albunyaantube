/**
 * Toast notification utility
 * Non-blocking, accessible toast notifications
 */

export type ToastType = 'success' | 'error' | 'info' | 'warning';

export interface ToastOptions {
  duration?: number;
  position?: 'top-right' | 'top-left' | 'bottom-right' | 'bottom-left';
}

const DEFAULT_DURATION = 3000;

function showToast(message: string, type: ToastType, options: ToastOptions = {}) {
  const { duration = DEFAULT_DURATION, position = 'top-right' } = options;

  // Log to console for debugging
  const logMap = {
    success: console.log,
    error: console.error,
    info: console.info,
    warning: console.warn
  };
  logMap[type](`[${type.toUpperCase()}]`, message);

  if (typeof window === 'undefined') return;

  // Create toast element
  const toastEl = document.createElement('div');
  toastEl.className = `toast toast-${type}`;
  toastEl.textContent = message;
  toastEl.setAttribute('role', 'alert');
  toastEl.setAttribute('aria-live', 'polite');
  toastEl.setAttribute('aria-atomic', 'true');

  // Position mapping
  const positionStyles: Record<string, string> = {
    'top-right': 'top: 20px; right: 20px;',
    'top-left': 'top: 20px; left: 20px;',
    'bottom-right': 'bottom: 20px; right: 20px;',
    'bottom-left': 'bottom: 20px; left: 20px;'
  };

  // Type-specific colors
  const colorMap: Record<ToastType, string> = {
    success: '#4caf50',
    error: '#f44336',
    warning: '#ff9800',
    info: '#2196f3'
  };

  toastEl.style.cssText = `
    position: fixed;
    ${positionStyles[position]}
    min-width: 250px;
    max-width: 400px;
    padding: 12px 20px;
    background: ${colorMap[type]};
    color: white;
    border-radius: 4px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.15);
    z-index: 9999;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    font-size: 14px;
    line-height: 1.5;
    opacity: 0;
    transform: translateY(-20px);
    transition: opacity 0.3s ease-out, transform 0.3s ease-out;
    pointer-events: auto;
    cursor: pointer;
  `;

  document.body.appendChild(toastEl);

  // Trigger animation
  requestAnimationFrame(() => {
    toastEl.style.opacity = '1';
    toastEl.style.transform = 'translateY(0)';
  });

  // Click to dismiss
  toastEl.addEventListener('click', () => dismiss());

  // Auto-dismiss after duration
  const timeoutId = setTimeout(() => dismiss(), duration);

  function dismiss() {
    clearTimeout(timeoutId);
    toastEl.style.opacity = '0';
    toastEl.style.transform = 'translateY(-20px)';
    setTimeout(() => toastEl.remove(), 300);
  }
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
