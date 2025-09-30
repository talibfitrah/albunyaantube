import '@testing-library/jest-dom';
import { render, screen } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { describe, it, expect } from 'vitest';
import MainTabBar from '@/components/navigation/MainTabBar.vue';
import { MAIN_TABS, TAB_ICON_PATHS, type MainTabKey } from '@/constants/tabs';
import { messages } from '@/locales/messages';

function renderTabBar(activeKey: MainTabKey) {
  return render(MainTabBar, {
    props: { activeKey },
    global: {
      plugins: [
        createI18n({
          legacy: false,
          locale: 'en',
          messages
        })
      ]
    }
  });
}

describe('MainTabBar', () => {
  it('exposes four canonical tabs with labels and icons', () => {
    expect(MAIN_TABS).toHaveLength(4);
    const iconKeys = Object.keys(TAB_ICON_PATHS);
    MAIN_TABS.forEach(tab => {
      expect(tab.labelKey).toMatch(/^navigation\./);
      expect(iconKeys).toContain(tab.icon);
    });
  });

  it('renders identical icon sets regardless of active tab', () => {
    const first = renderTabBar('home');
    const iconsA = Array.from(first.container.querySelectorAll('[data-icon]')).map(
      node => node.getAttribute('data-icon')
    );
    first.unmount();

    const second = renderTabBar('videos');
    const iconsB = Array.from(second.container.querySelectorAll('[data-icon]')).map(
      node => node.getAttribute('data-icon')
    );

    expect(iconsA).toEqual(iconsB);
  });

  it('reflects active state styling', () => {
    renderTabBar('channels');
    const activeButton = screen.getByRole('button', { name: /channels/i });
    expect(activeButton).toHaveClass('active');
    const inactiveButton = screen.getByRole('button', { name: /home/i });
    expect(inactiveButton).not.toHaveClass('active');
  });
});
