import { createRouter, createWebHistory, type NavigationGuardNext, type RouteLocationNormalized } from 'vue-router';
import LoginView from '@/views/LoginView.vue';
import DashboardView from '@/views/DashboardView.vue';
import ContentSearchView from '@/views/ContentSearchView.vue';
import CategoriesView from '@/views/CategoriesView.vue';
import PendingApprovalsView from '@/views/PendingApprovalsView.vue';
import ContentLibraryView from '@/views/ContentLibraryView.vue';
import UsersManagementView from '@/views/UsersManagementView.vue';
import AuditLogView from '@/views/AuditLogView.vue';
import ActivityLogView from '@/views/ActivityLogView.vue';
import ProfileSettingsView from '@/views/ProfileSettingsView.vue';
import NotificationsSettingsView from '@/views/NotificationsSettingsView.vue';
import YouTubeAPISettingsView from '@/views/YouTubeAPISettingsView.vue';
import SystemSettingsView from '@/views/SystemSettingsView.vue';
import AdminLayout from '@/layouts/AdminLayout.vue';
import { useAuthStore } from '@/stores/auth';

const protectedChildRoutes = [
  {
    path: '',
    name: 'dashboard',
    component: DashboardView,
    meta: { requiresAuth: true }
  },
  {
    path: 'content-search',
    name: 'content-search',
    component: ContentSearchView,
    meta: { requiresAuth: true }
  },
  {
    path: 'categories',
    name: 'categories',
    component: CategoriesView,
    meta: { requiresAuth: true }
  },
  {
    path: 'approvals',
    name: 'approvals',
    component: PendingApprovalsView,
    meta: { requiresAuth: true }
  },
  {
    path: 'content-library',
    name: 'content-library',
    component: ContentLibraryView,
    meta: { requiresAuth: true }
  },
  {
    path: 'users',
    name: 'users',
    component: UsersManagementView,
    meta: { requiresAuth: true }
  },
  {
    path: 'audit',
    name: 'audit',
    component: AuditLogView,
    meta: { requiresAuth: true }
  },
  {
    path: 'activity',
    name: 'activity',
    component: ActivityLogView,
    meta: { requiresAuth: true }
  },
  {
    path: 'settings/profile',
    name: 'settings-profile',
    component: ProfileSettingsView,
    meta: { requiresAuth: true }
  },
  {
    path: 'settings/notifications',
    name: 'settings-notifications',
    component: NotificationsSettingsView,
    meta: { requiresAuth: true }
  },
  {
    path: 'settings/youtube-api',
    name: 'settings-youtube-api',
    component: YouTubeAPISettingsView,
    meta: { requiresAuth: true }
  },
  {
    path: 'settings/system',
    name: 'settings-system',
    component: SystemSettingsView,
    meta: { requiresAuth: true }
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { public: true }
    },
    {
      path: '/',
      component: AdminLayout,
      meta: { requiresAuth: true },
      children: protectedChildRoutes
    }
  ]
});

router.beforeEach((to: RouteLocationNormalized, _from: RouteLocationNormalized, next: NavigationGuardNext) => {
  const authStore = useAuthStore();

  if (to.meta.public) {
    if (authStore.isAuthenticated) {
      next({ name: 'dashboard' });
      return;
    }
    next();
    return;
  }

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next({ name: 'login', query: { redirect: to.fullPath } });
    return;
  }

  next();
});

export default router;
