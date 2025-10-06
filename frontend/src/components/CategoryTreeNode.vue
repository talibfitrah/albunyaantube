<template>
  <div class="tree-node">
    <div class="node-content" :class="{ selected: isSelected }">
      <button
        v-if="hasChildren"
        @click="emit('toggle-expand', category.id)"
        class="expand-btn"
        :aria-label="isExpanded ? 'Collapse' : 'Expand'"
      >
        <span class="expand-icon" :class="{ expanded: isExpanded }">▶</span>
      </button>
      <div v-else class="expand-placeholder"></div>

      <label class="node-label" @click="emit('toggle-select', category.id)">
        <input
          type="checkbox"
          :checked="isSelected"
          @click.stop="emit('toggle-select', category.id)"
          class="node-checkbox"
        />
        <span class="node-text">{{ category.name }}</span>
      </label>
    </div>

    <div v-if="hasChildren && isExpanded" class="node-children">
      <CategoryTreeNode
        v-for="child in category.children"
        :key="child.id"
        :category="child"
        :expanded-nodes="expandedNodes"
        :selected-ids="selectedIds"
        :multi-select="multiSelect"
        @toggle-expand="emit('toggle-expand', $event)"
        @toggle-select="emit('toggle-select', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface Category {
  id: string;
  name?: string;
  label?: string;
  children?: Category[];
}

interface Props {
  category: Category;
  expandedNodes: Set<string>;
  selectedIds: Set<string>;
  multiSelect: boolean;
}

interface Emits {
  (e: 'toggle-expand', categoryId: string): void;
  (e: 'toggle-select', categoryId: string): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const isExpanded = computed(() => props.expandedNodes.has(props.category.id));
const isSelected = computed(() => props.selectedIds.has(props.category.id));
const hasChildren = computed(() => props.category.children && props.category.children.length > 0);
</script>

<style scoped>
.tree-node {
  margin: 0.25rem 0;
}

.node-content {
  display: flex;
  align-items: center;
  padding: 0.5rem;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.node-content:hover {
  background: var(--color-surface-variant);
}

.node-content.selected {
  background: rgba(var(--color-primary-rgb), 0.1);
}

.expand-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 0.25rem;
  color: var(--color-text-secondary);
  transition: transform 0.2s;
}

.expand-icon {
  display: inline-block;
  transition: transform 0.2s;
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

.expand-placeholder {
  width: 1.5rem;
}

.node-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex: 1;
  cursor: pointer;
}

.node-checkbox {
  width: 1rem;
  height: 1rem;
  cursor: pointer;
}

.node-text {
  font-size: 0.9375rem;
  color: var(--color-text);
}

.node-children {
  margin-inline-start: 1.5rem;
  border-inline-start: 1px solid var(--color-border);
  padding-inline-start: 0.5rem;
}
</style>
