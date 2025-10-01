import { createRouter, createWebHistory, type NavigationGuardNext, type RouteLocationNormalized } from 'vue-router';
import LoginView from '@/views/LoginView.vue';
import DashboardView from '@/views/DashboardView.vue';
import RegistryLandingView from '@/views/RegistryLandingView.vue';
import ModerationQueueView from '@/views/ModerationQueueView.vue';
import ExclusionsWorkspaceView from '@/views/ExclusionsWorkspaceView.vue';
import UsersManagementView from '@/views/UsersManagementView.vue';
import AuditLogView from '@/views/AuditLogView.vue';
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
    path: 'registry',
    name: 'registry',
    component: RegistryLandingView,
    meta: { requiresAuth: true }
  },
  {
    path: 'exclusions',
    name: 'exclusions',
    component: ExclusionsWorkspaceView,
    meta: { requiresAuth: true }
  },
  {
    path: 'moderation',
    name: 'moderation',
    component: ModerationQueueView,
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
