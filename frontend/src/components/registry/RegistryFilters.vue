<template>
  <div class="registry-filters">
    <div class="search-field">
      <input
        v-model="searchModel"
        type="search"
        :placeholder="t('registry.search.placeholder')"
        aria-label="Search"
      />
      <button
        v-if="showClear"
        type="button"
        class="clear"
        @click="clearQuery"
        :aria-label="t('registry.search.clear')"
      >
        ×
      </button>
    </div>
    <label class="category-filter">
      <span class="sr-only">{{ t('registry.search.categoryLabel') }}</span>
      <template v-if="!isCategoryLoading">
        <select v-model="categoryModel">
          <option value="">{{ t('registry.search.categoryLabel') }}</option>
          <option v-for="category in categories" :key="category.id" :value="category.slug">
            {{ category.label }}
          </option>
        </select>
      </template>
      <div
        v-else
        class="category-skeleton"
        data-testid="category-skeleton"
        aria-hidden="true"
      ></div>
    </label>
    <p v-if="categoryError" class="category-error" role="status">
      {{ categoryError }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';
import { storeToRefs } from 'pinia';
import { useRegistryFiltersStore } from '@/stores/registryFilters';

const { t } = useI18n();
const filtersStore = useRegistryFiltersStore();
const { query, categoryId, categories, isCategoryLoading, categoryError } = storeToRefs(filtersStore);

const searchModel = computed({
  get: () => query.value,
  set: value => filtersStore.setQuery(value)
});

const categoryModel = computed({
  get: () => categoryId.value ?? '',
  set: value => filtersStore.setCategoryId(value || null)
});

const showClear = computed(() => Boolean(searchModel.value));

onMounted(() => {
  filtersStore.fetchCategories();
});

function clearQuery() {
  filtersStore.setQuery('');
}
</script>

<style scoped>
.registry-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: center;
}

.search-field {
  position: relative;
  display: flex;
  align-items: center;
  background: var(--color-surface-alt);
  border-radius: 999px;
  padding: 0.25rem 0.75rem;
  border: 1px solid var(--color-border);
}

.search-field input {
  border: none;
  background: transparent;
  padding: 0.5rem 0.75rem;
  font-size: 0.95rem;
  outline: none;
  min-width: 220px;
  color: var(--color-text-primary);
}

.search-field .clear {
  border: none;
  background: transparent;
  font-size: 1.25rem;
  cursor: pointer;
  color: var(--color-text-secondary);
  opacity: 0.8;
}

.category-filter {
  display: flex;
  align-items: center;
}

.category-filter select {
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--color-surface-alt);
  padding: 0.5rem 1rem;
  font-size: 0.95rem;
  color: var(--color-text-primary);
}

.category-skeleton {
  width: 200px;
  height: 44px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    var(--color-surface-alt) 25%,
    var(--color-border) 37%,
    var(--color-surface-alt) 63%
  );
  background-size: 400% 100%;
  animation: shimmer 1.4s ease infinite;
}

.category-error {
  width: 100%;
  color: var(--color-danger);
  font-size: 0.85rem;
  margin: 0;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

@keyframes shimmer {
  0% {
    background-position: -200% 0;
  }
  100% {
    background-position: 200% 0;
  }
}

@media (max-width: 640px) {
  .registry-filters {
    width: 100%;
    flex-direction: column;
    align-items: stretch;
  }

  .search-field {
    width: 100%;
  }

  .search-field input {
    width: 100%;
  }

  .category-filter {
    width: 100%;
  }

  .category-filter select,
  .category-skeleton {
    width: 100%;
  }
}
</style>
