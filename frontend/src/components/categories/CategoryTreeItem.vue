<template>
  <div class="category-item">
    <div class="category-row" :style="{ paddingInlineStart: `${level * 1.5}rem` }">
      <button
        v-if="hasChildren"
        type="button"
        class="expand-button"
        @click="toggleExpanded"
      >
        <span :class="['expand-icon', { expanded: isExpanded }]">▶</span>
      </button>
      <div v-else class="expand-spacer"></div>

      <div class="category-content">
        <span v-if="category.icon" class="category-icon">{{ category.icon }}</span>
        <span class="category-name">{{ category.label }}</span>
        <span v-if="hasChildren" class="subcategory-count">
          ({{ category.subcategories.length }})
        </span>
      </div>

      <div class="category-actions">
        <button
          type="button"
          class="action-btn"
          :title="t('categories.addSubcategory')"
          @click="emit('add-child', category.id)"
        >
          <span>+</span>
        </button>
        <button
          type="button"
          class="action-btn"
          :title="t('categories.edit')"
          @click="emit('edit', category)"
        >
          <span>✎</span>
        </button>
        <button
          type="button"
          class="action-btn delete"
          :title="t('categories.delete')"
          @click="emit('delete', category.id)"
        >
          <span>×</span>
        </button>
      </div>
    </div>

    <div v-if="isExpanded && hasChildren" class="subcategories">
      <CategoryTreeItem
        v-for="subcategory in category.subcategories"
        :key="subcategory.id"
        :category="subcategory"
        :level="level + 1"
        @edit="emit('edit', $event)"
        @delete="emit('delete', $event)"
        @add-child="emit('add-child', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';

interface Category {
  id: string;
  label: string;
  icon?: string;
  subcategories?: Category[];
}

interface Props {
  category: Category;
  level: number;
}

const props = defineProps<Props>();

const emit = defineEmits<{
  edit: [category: Category];
  delete: [categoryId: string];
  'add-child': [parentId: string];
}>();

const { t } = useI18n();

const isExpanded = ref(true);

const hasChildren = computed(() => {
  return props.category.subcategories && props.category.subcategories.length > 0;
});

function toggleExpanded() {
  isExpanded.value = !isExpanded.value;
}
</script>

<style scoped>
.category-item {
  display: flex;
  flex-direction: column;
}

.category-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 0.5rem;
  border-radius: 0.5rem;
  transition: background 0.2s ease;
}

@media (hover: hover) {
  .category-row:hover {
    background: var(--color-surface-alt);
  }
}

.expand-button {
  width: 2rem;
  height: 2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  color: var(--color-text-secondary);
  transition: color 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

@media (hover: hover) {
  .expand-button:hover {
    color: var(--color-text-primary);
  }
}

.expand-icon {
  display: inline-block;
  font-size: 0.75rem;
  transition: transform 0.2s ease;
}

.expand-icon.expanded {
  transform: rotate(90deg);
}

/* RTL: Flip expand icon direction */
[dir='rtl'] .expand-icon {
  transform: scaleX(-1);
}

[dir='rtl'] .expand-icon.expanded {
  transform: scaleX(-1) rotate(90deg);
}

.expand-spacer {
  width: 2rem;
}

.category-content {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.category-icon {
  font-size: 1.25rem;
}

.category-name {
  font-weight: 500;
  color: var(--color-text-primary);
}

.subcategory-count {
  font-size: 0.8125rem;
  color: var(--color-text-secondary);
}

.category-actions {
  display: flex;
  gap: 0.375rem;
  opacity: 0;
  transition: opacity 0.2s ease;
}

@media (hover: hover) {
  .category-row:hover .category-actions {
    opacity: 1;
  }
}

/* On touch devices, always show actions */
@media (hover: none) {
  .category-actions {
    opacity: 1;
  }
}

.action-btn {
  width: 2.25rem;
  height: 2.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: 1px solid var(--color-border);
  border-radius: 0.375rem;
  cursor: pointer;
  font-size: 1rem;
  color: var(--color-text-secondary);
  transition: all 0.2s ease;
  -webkit-tap-highlight-color: transparent;
}

@media (hover: hover) {
  .action-btn:hover {
    background: var(--color-brand-soft);
    border-color: var(--color-brand);
    color: var(--color-brand);
  }

  .action-btn.delete:hover {
    background: var(--color-danger-soft);
    border-color: var(--color-danger);
    color: var(--color-danger);
  }
}

.subcategories {
  display: flex;
  flex-direction: column;
}

/* Mobile/Tablet Responsive */
@media (max-width: 1023px) {
  .category-row {
    padding: 0.875rem 0.5rem;
    gap: 0.5rem;
  }

  .expand-button {
    width: 2.5rem;
    height: 2.5rem;
  }

  .expand-spacer {
    width: 2.5rem;
  }

  .expand-icon {
    font-size: 0.875rem;
  }

  .category-icon {
    font-size: 1.375rem;
  }

  .category-name {
    font-size: 0.9375rem;
  }

  .action-btn {
    width: 2.75rem;
    height: 2.75rem;
    font-size: 1.125rem;
  }
}

@media (max-width: 767px) {
  .category-row {
    padding: 1rem 0.25rem;
  }

  .category-content {
    gap: 0.5rem;
  }

  .category-name {
    font-size: 0.875rem;
  }

  .subcategory-count {
    font-size: 0.75rem;
  }
}
</style>
