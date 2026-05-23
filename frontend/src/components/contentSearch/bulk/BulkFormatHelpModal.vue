<script setup lang="ts">
import { ref } from 'vue'

defineEmits<{ (e: 'close'): void }>()

const activeTab = ref<'csv' | 'excel' | 'json'>('csv')
</script>

<template>
  <div
    class="modal d-block"
    tabindex="-1"
    style="background: rgba(0, 0, 0, 0.5)"
    @click.self="$emit('close')"
  >
    <div class="modal-dialog modal-lg">
      <div class="modal-content">
        <div class="modal-header">
          <h5 class="modal-title">{{ $t('contentSearch.bulk.formatHelp.title') }}</h5>
          <button type="button" class="btn-close" @click="$emit('close')"></button>
        </div>
        <div class="modal-body">
          <ul class="nav nav-tabs mb-3" role="tablist">
            <li class="nav-item" role="presentation">
              <button
                type="button"
                class="nav-link"
                :class="{ active: activeTab === 'csv' }"
                @click="activeTab = 'csv'"
              >
                {{ $t('contentSearch.bulk.formatHelp.csvTab') }}
              </button>
            </li>
            <li class="nav-item" role="presentation">
              <button
                type="button"
                class="nav-link"
                :class="{ active: activeTab === 'excel' }"
                @click="activeTab = 'excel'"
              >
                {{ $t('contentSearch.bulk.formatHelp.excelTab') }}
              </button>
            </li>
            <li class="nav-item" role="presentation">
              <button
                type="button"
                class="nav-link"
                :class="{ active: activeTab === 'json' }"
                @click="activeTab = 'json'"
              >
                {{ $t('contentSearch.bulk.formatHelp.jsonTab') }}
              </button>
            </li>
          </ul>

          <div v-if="activeTab === 'csv'">
            <p>{{ $t('contentSearch.bulk.formatHelp.csvSpec') }}</p>
            <pre class="bg-light p-2 rounded"><code>URL
https://www.youtube.com/channel/UCxxxx
https://www.youtube.com/playlist?list=PLxxxx
https://www.youtube.com/watch?v=xxxxxxxxxxx
https://www.youtube.com/live/xxxxxxxxxxx</code></pre>
            <a href="/samples/sample-bulk-urls.csv" download class="btn btn-sm btn-outline-primary">
              {{ $t('contentSearch.bulk.formatHelp.downloadSample') }}
            </a>
          </div>

          <div v-else-if="activeTab === 'excel'">
            <p>{{ $t('contentSearch.bulk.formatHelp.excelSpec') }}</p>
            <a
              href="/samples/sample-bulk-urls.xlsx"
              download
              class="btn btn-sm btn-outline-primary"
            >
              {{ $t('contentSearch.bulk.formatHelp.downloadSample') }}
            </a>
          </div>

          <div v-else>
            <p>{{ $t('contentSearch.bulk.formatHelp.jsonSpec') }}</p>
            <pre class="bg-light p-2 rounded"><code>{
  "urls": [
    "https://www.youtube.com/channel/UCxxxx",
    "https://www.youtube.com/watch?v=xxxxxxxxxxx"
  ]
}</code></pre>
            <a
              href="/samples/sample-bulk-urls.json"
              download
              class="btn btn-sm btn-outline-primary"
            >
              {{ $t('contentSearch.bulk.formatHelp.downloadSample') }}
            </a>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
