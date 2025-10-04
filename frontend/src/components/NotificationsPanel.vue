<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

interface Notification {
  id: string;
  type: 'approval' | 'category' | 'user' | 'system';
  title: string;
  message: string;
  timestamp: string;
  read: boolean;
  actionUrl?: string;
}

// State
const isOpen = ref(false);
const notifications = ref<Notification[]>([]);
const filter = ref<'all' | 'unread'>('all');

// Computed
const unreadCount = computed(() => {
  return notifications.value.filter(n => !n.read).length;
});

const filteredNotifications = computed(() => {
  if (filter.value === 'unread') {
    return notifications.value.filter(n => !n.read);
  }
  return notifications.value;
});

const hasNotifications = computed(() => {
  return filteredNotifications.value.length > 0;
});

// Methods
onMounted(() => {
  loadNotifications();
});

async function loadNotifications() {
  // Mock data - replace with actual API call
  notifications.value = [
    {
      id: '1',
      type: 'approval',
      title: t('notifications.types.newApproval'),
      message: 'New video "Islamic History 101" pending review',
      timestamp: new Date(Date.now() - 3600000).toISOString(),
      read: false,
      actionUrl: '/approvals'
    },
    {
      id: '2',
      type: 'category',
      title: t('notifications.types.categoryChange'),
      message: 'Category "Quran Recitation" was updated',
      timestamp: new Date(Date.now() - 7200000).toISOString(),
      read: false
    },
    {
      id: '3',
      type: 'user',
      title: t('notifications.types.userActivity'),
      message: 'New moderator "ahmad@example.com" was added',
      timestamp: new Date(Date.now() - 86400000).toISOString(),
      read: true,
      actionUrl: '/users'
    }
  ];
}

function togglePanel() {
  isOpen.value = !isOpen.value;
}

function closePanel() {
  isOpen.value = false;
}

function markAsRead(notificationId: string) {
  const notification = notifications.value.find(n => n.id === notificationId);
  if (notification) {
    notification.read = true;
  }
}

function markAllAsRead() {
  notifications.value.forEach(n => {
    n.read = true;
  });
}

function handleNotificationClick(notification: Notification) {
  markAsRead(notification.id);
  if (notification.actionUrl) {
    closePanel();
    // Router navigation would happen here
  }
}

function getNotificationIcon(type: Notification['type']): string {
  const icons = {
    approval: '📋',
    category: '📁',
    user: '👤',
    system: '⚙️'
  };
  return icons[type];
}

function formatTimestamp(timestamp: string): string {
  const date = new Date(timestamp);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return t('notifications.time.justNow');
  if (diffMins < 60) return t('notifications.time.minutesAgo', { minutes: diffMins });
  if (diffHours < 24) return t('notifications.time.hoursAgo', { hours: diffHours });
  if (diffDays < 7) return t('notifications.time.daysAgo', { days: diffDays });
  return date.toLocaleDateString();
}

// Close panel when clicking outside
function handleClickOutside(event: MouseEvent) {
  const target = event.target as HTMLElement;
  if (!target.closest('.notifications-panel') && !target.closest('.notifications-button')) {
    closePanel();
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside);
});
</script>

<template>
  <div class="notifications-container">
    <!-- Notification Bell Button -->
    <button
      @click="togglePanel"
      class="notifications-button"
      :aria-label="t('notifications.togglePanel')"
      :aria-expanded="isOpen"
    >
      <span class="bell-icon">🔔</span>
      <span v-if="unreadCount > 0" class="unread-badge">{{ unreadCount > 9 ? '9+' : unreadCount }}</span>
    </button>

    <!-- Notifications Panel -->
    <transition name="panel-fade">
      <div v-if="isOpen" class="notifications-panel">
        <!-- Panel Header -->
        <div class="panel-header">
          <h3>{{ t('notifications.heading') }}</h3>
          <div class="header-actions">
            <button
              v-if="unreadCount > 0"
              @click="markAllAsRead"
              class="btn-link"
            >
              {{ t('notifications.actions.markAllRead') }}
            </button>
            <button
              @click="closePanel"
              class="btn-close"
              :aria-label="t('notifications.actions.close')"
            >
              ✕
            </button>
          </div>
        </div>

        <!-- Filter Tabs -->
        <div class="filter-tabs">
          <button
            @click="filter = 'all'"
            :class="{ active: filter === 'all' }"
            class="filter-tab"
          >
            {{ t('notifications.filters.all') }}
          </button>
          <button
            @click="filter = 'unread'"
            :class="{ active: filter === 'unread' }"
            class="filter-tab"
          >
            {{ t('notifications.filters.unread') }}
            <span v-if="unreadCount > 0" class="tab-badge">{{ unreadCount }}</span>
          </button>
        </div>

        <!-- Notifications List -->
        <div class="notifications-list">
          <div v-if="!hasNotifications" class="empty-state">
            <span class="empty-icon">📭</span>
            <p>{{ t('notifications.empty') }}</p>
          </div>

          <button
            v-for="notification in filteredNotifications"
            :key="notification.id"
            @click="handleNotificationClick(notification)"
            class="notification-item"
            :class="{ unread: !notification.read }"
          >
            <div class="notification-icon">{{ getNotificationIcon(notification.type) }}</div>
            <div class="notification-content">
              <div class="notification-title">{{ notification.title }}</div>
              <div class="notification-message">{{ notification.message }}</div>
              <div class="notification-time">{{ formatTimestamp(notification.timestamp) }}</div>
            </div>
            <div v-if="!notification.read" class="unread-indicator"></div>
          </button>
        </div>

        <!-- Panel Footer -->
        <div class="panel-footer">
          <router-link to="/activity" @click="closePanel" class="view-all-link">
            {{ t('notifications.actions.viewAll') }} →
          </router-link>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.notifications-container {
  position: relative;
}

/* Notification Button */
.notifications-button {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2.5rem;
  height: 2.5rem;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.notifications-button:hover {
  background: var(--color-surface-variant);
}

.bell-icon {
  font-size: 1.25rem;
}

.unread-badge {
  position: absolute;
  top: 0.25rem;
  right: 0.25rem;
  min-width: 1.125rem;
  height: 1.125rem;
  padding: 0 0.25rem;
  background: var(--color-danger);
  color: white;
  font-size: 0.625rem;
  font-weight: 600;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* Notifications Panel */
.notifications-panel {
  position: absolute;
  top: calc(100% + 0.5rem);
  right: 0;
  width: 380px;
  max-height: 500px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  display: flex;
  flex-direction: column;
  z-index: 1000;
}

/* Panel Header */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem;
  border-bottom: 1px solid var(--color-border);
}

.panel-header h3 {
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-text);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.btn-link {
  background: none;
  border: none;
  color: var(--color-primary);
  font-size: 0.8125rem;
  cursor: pointer;
  padding: 0.25rem 0.5rem;
}

.btn-link:hover {
  text-decoration: underline;
}

.btn-close {
  background: none;
  border: none;
  color: var(--color-text-secondary);
  font-size: 1.25rem;
  cursor: pointer;
  padding: 0.25rem;
  line-height: 1;
  transition: color 0.2s;
}

.btn-close:hover {
  color: var(--color-text);
}

/* Filter Tabs */
.filter-tabs {
  display: flex;
  padding: 0 1rem;
  border-bottom: 1px solid var(--color-border);
}

.filter-tab {
  flex: 1;
  padding: 0.75rem 1rem;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  color: var(--color-text-secondary);
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
}

.filter-tab.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
}

.tab-badge {
  background: var(--color-primary);
  color: white;
  font-size: 0.625rem;
  padding: 0.125rem 0.375rem;
  border-radius: 9999px;
  min-width: 1.25rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

/* Notifications List */
.notifications-list {
  flex: 1;
  overflow-y: auto;
  max-height: 400px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 3rem 1rem;
  color: var(--color-text-secondary);
}

.empty-icon {
  font-size: 3rem;
  margin-bottom: 0.5rem;
  opacity: 0.5;
}

.empty-state p {
  font-size: 0.9375rem;
}

/* Notification Item */
.notification-item {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  width: 100%;
  padding: 0.875rem 1rem;
  background: none;
  border: none;
  border-bottom: 1px solid var(--color-border);
  text-align: left;
  cursor: pointer;
  transition: background-color 0.2s;
  position: relative;
}

.notification-item:hover {
  background: var(--color-surface-variant);
}

.notification-item.unread {
  background: rgba(var(--color-primary-rgb), 0.05);
}

.notification-icon {
  font-size: 1.5rem;
  flex-shrink: 0;
}

.notification-content {
  flex: 1;
  min-width: 0;
}

.notification-title {
  font-weight: 600;
  color: var(--color-text);
  font-size: 0.875rem;
  margin-bottom: 0.25rem;
}

.notification-message {
  color: var(--color-text-secondary);
  font-size: 0.8125rem;
  line-height: 1.4;
  margin-bottom: 0.25rem;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.notification-time {
  color: var(--color-text-secondary);
  font-size: 0.75rem;
}

.unread-indicator {
  position: absolute;
  top: 50%;
  right: 1rem;
  transform: translateY(-50%);
  width: 0.5rem;
  height: 0.5rem;
  background: var(--color-primary);
  border-radius: 50%;
}

/* Panel Footer */
.panel-footer {
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--color-border);
}

.view-all-link {
  display: block;
  width: 100%;
  text-align: center;
  color: var(--color-primary);
  font-size: 0.875rem;
  font-weight: 500;
  text-decoration: none;
  padding: 0.5rem;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.view-all-link:hover {
  background: var(--color-surface-variant);
}

/* Panel Animation */
.panel-fade-enter-active,
.panel-fade-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.panel-fade-enter-from,
.panel-fade-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

/* Mobile Responsiveness */
@media (max-width: 640px) {
  .notifications-panel {
    position: fixed;
    top: 4rem;
    left: 0;
    right: 0;
    width: 100%;
    max-height: calc(100vh - 5rem);
    border-radius: 0;
  }
}

/* RTL Support */
[dir="rtl"] .notifications-panel {
  right: auto;
  left: 0;
}

[dir="rtl"] .unread-badge {
  right: auto;
  left: 0.25rem;
}

[dir="rtl"] .unread-indicator {
  right: auto;
  left: 1rem;
}

@media (max-width: 640px) {
  [dir="rtl"] .notifications-panel {
    left: 0;
    right: 0;
  }
}
</style>
