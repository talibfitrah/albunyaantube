import { createRouter, createWebHistory, type NavigationGuardNext, type RouteLocationNormalized } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

// Eager load critical routes (login, layout)
import LoginView from '@/views/LoginView.vue';
import AdminLayout from '@/layouts/AdminLayout.vue';

// Lazy load all view components for code splitting
const DashboardView = () => import('@/views/DashboardView.vue');
const ContentSearchView = () => import('@/views/ContentSearchView.vue');
const CategoriesView = () => import('@/views/CategoriesView.vue');
const PendingApprovalsView = () => import('@/views/PendingApprovalsView.vue');
const ContentLibraryView = () => import('@/views/ContentLibraryView.vue');
const UsersManagementView = () => import('@/views/UsersManagementView.vue');
const AuditLogView = () => import('@/views/AuditLogView.vue');
const ActivityLogView = () => import('@/views/ActivityLogView.vue');
const ProfileSettingsView = () => import('@/views/ProfileSettingsView.vue');
const NotificationsSettingsView = () => import('@/views/NotificationsSettingsView.vue');
const YouTubeAPISettingsView = () => import('@/views/YouTubeAPISettingsView.vue');
const SystemSettingsView = () => import('@/views/SystemSettingsView.vue');

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
