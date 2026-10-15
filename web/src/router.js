import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken, getUser } from './api'

// 全部使用 hash 模式，方便直接用静态文件部署
const routes = [
  { path: '/', redirect: '/activities' },
  { path: '/login', name: 'login', component: () => import('./views/Login.vue'), meta: { public: true } },
  { path: '/activities', name: 'activities', component: () => import('./views/ActivityList.vue') },
  { path: '/activity/:id', name: 'activityDetail', component: () => import('./views/ActivityDetail.vue') },
  { path: '/orders', name: 'orders', component: () => import('./views/MyOrders.vue') },
  { path: '/merchant', name: 'merchant', component: () => import('./views/Merchant.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/activities' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes: routes,
  scrollBehavior: function () {
    return { top: 0 }
  }
})

router.beforeEach(function (to) {
  const token = getToken()
  const user = getUser()

  // 未登录只能访问登录页
  if (!token && !to.meta.public) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 已登录还去登录页：按角色送回各自的首页
  if (token && to.meta.public) {
    return user && user.role === 'MERCHANT' ? '/merchant' : '/activities'
  }
  // 商家后台只对商家角色开放
  if (to.path === '/merchant' && (!user || user.role !== 'MERCHANT')) {
    return '/activities'
  }
  return true
})

export default router
