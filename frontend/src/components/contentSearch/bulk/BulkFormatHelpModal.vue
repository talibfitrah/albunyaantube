<script setup lang="ts">
import { onMounted, ref } from 'vue'

const emit = defineEmits<{ (e: 'close'): void }>()

const activeTab = ref<'csv' | 'excel' | 'json'>('csv')
const containerRef = ref<HTMLElement | null>(null)

// Focus the dialog container on open so keyboard users land inside the
// modal and Esc-to-close fires from any focused descendant.
onMounted(() => {
  containerRef.value?.focus()
})

// Cubic R1 P2: trap Tab focus inside the dialog so it doesn't escape to
// controls behind the overlay (parity with the Bootstrap modal this
// replaced). Computes the focusable descendants on each Tab — cheap and
// avoids stale snapshots if the active panel changes (csv/excel/json
// tabs render different content).
const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.stopPropagation()
    emit('close')
    return
  }
  if (e.key !== 'Tab' || !containerRef.value) return

  const focusables = Array.from(
    containerRef.value.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)
  ).filter((el) => !el.hasAttribute('disabled') && el.offsetParent !== null)

  if (focusables.length === 0) {
    e.preventDefault()
    return
  }

  const first = focusables[0]
  const last = focusables[focusables.length - 1]
  const active = document.activeElement as HTMLElement | null

  if (e.shiftKey && (active === first || !containerRef.value.contains(active))) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && active === last) {
    e.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <teleport to="body">
    <div class="bulk-modal-overlay" @click.self="$emit('close')">
      <div
        ref="containerRef"
        class="bulk-modal-container"
        role="dialog"
        aria-modal="true"
        aria-labelledby="bulk-format-help-title"
        tabindex="-1"
        @keydown="onKeydown"
      >
        <div class="bulk-modal-header">
          <h2 id="bulk-format-help-title">{{ $t('contentSearch.bulk.formatHelp.title') }}</h2>
          <button type="button" class="bulk-modal-close" aria-label="Close" @click="$emit('close')">
            &times;
          </button>
        </div>
        <div class="bulk-modal-body">
          <div class="bulk-modal-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'csv'"
              class="bulk-modal-tab"
              :class="{ active: activeTab === 'csv' }"
              @click="activeTab = 'csv'"
            >
              {{ $t('contentSearch.bulk.formatHelp.csvTab') }}
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'excel'"
              class="bulk-modal-tab"
              :class="{ active: activeTab === 'excel' }"
              @click="activeTab = 'excel'"
            >
              {{ $t('contentSearch.bulk.formatHelp.excelTab') }}
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="activeTab === 'json'"
              class="bulk-modal-tab"
              :class="{ active: activeTab === 'json' }"
              @click="activeTab = 'json'"
            >
              {{ $t('contentSearch.bulk.formatHelp.jsonTab') }}
            </button>
          </div>

          <div v-if="activeTab === 'csv'" class="bulk-modal-panel">
            <p>{{ $t('contentSearch.bulk.formatHelp.csvSpec') }}</p>
            <pre class="bulk-modal-code"><code>URL
https://www.youtube.com/channel/UCxxxx
https://www.youtube.com/playlist?list=PLxxxx
https://www.youtube.com/watch?v=xxxxxxxxxxx
https://www.youtube.com/live/xxxxxxxxxxx</code></pre>
            <a href="/samples/sample-bulk-urls.csv" download class="bulk-modal-download">
              {{ $t('contentSearch.bulk.formatHelp.downloadSample') }}
            </a>
          </div>

          <div v-else-if="activeTab === 'excel'" class="bulk-modal-panel">
            <p>{{ $t('contentSearch.bulk.formatHelp.excelSpec') }}</p>
            <a
              href="/samples/sample-bulk-urls.xlsx"
              download
              class="bulk-modal-download"
            >
              {{ $t('contentSearch.bulk.formatHelp.downloadSample') }}
            </a>
          </div>

          <div v-else class="bulk-modal-panel">
            <p>{{ $t('contentSearch.bulk.formatHelp.jsonSpec') }}</p>
            <pre class="bulk-modal-code"><code>{
  "urls": [
    "https://www.youtube.com/channel/UCxxxx",
    "https://www.youtube.com/watch?v=xxxxxxxxxxx"
  ]
}</code></pre>
            <a
              href="/samples/sample-bulk-urls.json"
              download
              class="bulk-modal-download"
            >
              {{ $t('contentSearch.bulk.formatHelp.downloadSample') }}
            </a>
          </div>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.bulk-modal-overlay {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 1rem;
}

.bulk-modal-container {
  background: var(--color-surface);
  border-radius: 0.75rem;
  width: 100%;
  max-width: 640px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-elevated);
  overflow: hidden;
}

.bulk-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.25rem 1.5rem;
  border-bottom: 1px solid var(--color-border);
}

.bulk-modal-header h2 {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.bulk-modal-close {
  background: none;
  border: none;
  font-size: 1.5rem;
  color: var(--color-text-secondary);
  cursor: pointer;
  padding: 0.25rem 0.5rem;
  line-height: 1;
  border-radius: 0.25rem;
}

.bulk-modal-close:hover {
  color: var(--color-text-primary);
  background: var(--color-surface-alt);
}

[dir='rtl'] .bulk-modal-header {
  flex-direction: row-reverse;
}

.bulk-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 1.25rem 1.5rem;
}

.bulk-modal-tabs {
  display: flex;
  gap: 0.25rem;
  padding: 0.25rem;
  background: var(--color-surface-alt);
  border-radius: 0.5rem;
  width: fit-content;
  margin-bottom: 1.25rem;
}

.bulk-modal-tab {
  padding: 0.5rem 1rem;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  color: var(--color-text-secondary);
  transition: all 0.2s ease;
}

.bulk-modal-tab:hover:not(.active) {
  color: var(--color-text-primary);
}

.bulk-modal-tab.active {
  background: var(--color-surface);
  color: var(--color-brand);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.bulk-modal-panel p {
  margin: 0 0 0.875rem;
  color: var(--color-text-primary);
  font-size: 0.9375rem;
}

.bulk-modal-code {
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border);
  border-radius: 0.5rem;
  padding: 0.875rem 1rem;
  margin: 0 0 0.875rem;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: 0.8125rem;
  color: var(--color-text-primary);
  overflow-x: auto;
}

.bulk-modal-download {
  display: inline-block;
  padding: 0.5rem 1rem;
  background: transparent;
  border: 1.5px solid var(--color-brand);
  color: var(--color-brand);
  border-radius: 0.5rem;
  font-size: 0.875rem;
  font-weight: 600;
  text-decoration: none;
  transition: all 0.2s ease;
}

.bulk-modal-download:hover {
  background: var(--color-brand);
  color: var(--color-text-inverse);
}
</style>
