import { createRouter, createWebHistory, type NavigationGuardNext, type RouteLocationNormalized } from 'vue-router';
import { useAuthStore, type UserRole } from '@/stores/auth';

// Eager load critical routes (login, layout)
import LoginView from '@/views/LoginView.vue';
import AdminLayout from '@/layouts/AdminLayout.vue';

// Lazy load all view components for code splitting
const DashboardView = () => import('@/views/DashboardView.vue');
const ContentSearchView = () => import('@/views/ContentSearchView.vue');
const CategoriesView = () => import('@/views/CategoriesView.vue');
const PendingApprovalsView = () => import('@/views/PendingApprovalsView.vue');
const ContentLibraryView = () => import('@/views/ContentLibraryView.vue');
const ExclusionsWorkspaceView = () => import('@/views/ExclusionsWorkspaceView.vue');
const BulkImportExportView = () => import('@/views/BulkImportExportView.vue');
const VideoValidationView = () => import('@/views/VideoValidationView.vue');
const ArchivedContentReviewView = () => import('@/views/ArchivedContentReviewView.vue');
const ContentSortingView = () => import('@/views/ContentSortingView.vue');
const UsersManagementView = () => import('@/views/UsersManagementView.vue');
const AuditLogView = () => import('@/views/AuditLogView.vue');
const ActivityLogView = () => import('@/views/ActivityLogView.vue');
const ProfileSettingsView = () => import('@/views/ProfileSettingsView.vue');
const NotificationsSettingsView = () => import('@/views/NotificationsSettingsView.vue');
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
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
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
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'content-sorting',
    name: 'content-sorting',
    component: ContentSortingView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'exclusions',
    name: 'exclusions',
    component: ExclusionsWorkspaceView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'bulk-import-export',
    name: 'bulk-import-export',
    component: BulkImportExportView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'video-validation',
    name: 'video-validation',
    component: VideoValidationView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'archived-content',
    name: 'archived-content',
    component: ArchivedContentReviewView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'users',
    name: 'users',
    component: UsersManagementView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'audit',
    name: 'audit',
    component: AuditLogView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
  },
  {
    path: 'activity',
    name: 'activity',
    component: ActivityLogView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
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
    path: 'settings/system',
    name: 'settings-system',
    component: SystemSettingsView,
    meta: { requiresAuth: true, requiredRole: 'ADMIN' as UserRole }
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

router.beforeEach(async (to: RouteLocationNormalized, _from: RouteLocationNormalized, next: NavigationGuardNext) => {
  const authStore = useAuthStore();

  // Wait for auth to initialize before checking authentication
  if (!authStore.authInitialized) {
    // This should be fast since initializeAuthListener is called in main.ts
    // But we add this as a safeguard
    let attempts = 0;
    while (!authStore.authInitialized && attempts < 50) {
      await new Promise(resolve => setTimeout(resolve, 100));
      attempts++;
    }
  }

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

  // Role-based access control: check if route requires a specific role
  const requiredRole = to.meta.requiredRole as UserRole | undefined;
  if (requiredRole === 'ADMIN' && !authStore.isAdmin) {
    // Moderators and other non-admin users are redirected to dashboard
    next({ name: 'dashboard' });
    return;
  }

  next();
});

export default router;
