import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuth } from '@/stores/auth'
import { setUnauthorizedHandler } from '@/api/http'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    /**
     * 登录回跳落地页。路径由后端 AuthController#redirectToFrontend 拼出来（FRONTEND_CALLBACK_PATH），
     * 两边必须一致 —— 改一处就要改另一处。
     *
     * ⚠️ 不能放在 /oauth、/auth、/session、/file、/interview、/agent 这些前缀下：
     * 它们已被 vite.config.ts 的 server.proxy（生产是 Nginx）整段转发给后端，
     * 前端路由根本渲染不到。匹配是按字符串前缀，/oauth-callback、/oauthx 同样会被代理走。
     */
    path: '/login/callback',
    name: 'oauth-callback',
    component: () => import('@/views/AuthCallbackView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'chat',
    component: () => import('@/components/ChatApp.vue')
  },
  {
    // 兜底：未知路径回首页（未登录时会被守卫转到登录页）
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }

  const auth = useAuth()
  try {
    // status 为 unknown 时（首次进入或刷新页面）先问一次后端
    const ok = await auth.fetchMe()
    if (!ok) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  } catch {
    // 网络异常时不把人踢到登录页（会形成"登录页 ↔ 首页"的跳转死循环），
    // 放行到页面，由页面自身的连接错误提示兜底
    return true
  }
  return true
})

// 任意请求判定为"未登录"时统一跳登录页，并记住当前地址
setUnauthorizedHandler(() => {
  const current = router.currentRoute.value
  if (current.path === '/login' || current.path === '/login/callback') {
    return
  }
  void router.replace({ path: '/login', query: { redirect: current.fullPath } })
})

export default router
