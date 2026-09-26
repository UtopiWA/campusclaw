import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import MaterialsView from '../views/MaterialsView.vue'
import { authState, loadCurrentUser } from '../services/auth.js'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/materials' },
    { path: '/login', component: LoginView },
    { path: '/materials', component: MaterialsView, meta: { requiresAuth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/materials' },
  ],
})

export async function authGuard(to) {
  if (!authState.loaded) {
    await loadCurrentUser()
  }
  if (to.meta.requiresAuth && !authState.user) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && authState.user) {
    return '/materials'
  }
  return true
}

router.beforeEach(authGuard)

if (typeof window !== 'undefined') {
  window.addEventListener('campusclaw:unauthorized', () => {
    if (router.currentRoute.value.path !== '/login') {
      router.replace('/login')
    }
  })
}

export default router
