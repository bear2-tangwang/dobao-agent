<script setup lang="ts">
/**
 * 登录回跳落地页。
 *
 * 后端在 callback 里已经建好会话并 302 到这里，本页只做一件事：
 * 问一下"我是谁"，成功就进首页，失败就带回错误提示回登录页。
 */
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const failed = ref(false)

onMounted(async () => {
  const raw = route.query.redirect
  const target =
    typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//') ? raw : '/'

  try {
    const ok = await auth.fetchMe(true)
    if (ok) {
      await router.replace(target)
      return
    }
    failed.value = true
    await router.replace({ path: '/login', query: { error: 'state' } })
  } catch {
    failed.value = true
    await router.replace({ path: '/login', query: { error: 'server' } })
  }
})
</script>

<template>
  <div class="callback-page">
    <div class="callback-card">
      <div class="spinner" aria-hidden="true"></div>
      <p class="text">{{ failed ? '登录未完成，正在返回登录页…' : '正在完成登录…' }}</p>
    </div>
  </div>
</template>

<style scoped>
.callback-page {
  width: 100%;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0a111f 0%, #16233a 55%, #0d1a2e 100%);
}

.callback-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  color: rgba(203, 216, 235, 0.9);
  font-size: 14.5px;
  letter-spacing: 0.5px;
}

.spinner {
  width: 26px;
  height: 26px;
  border: 2px solid rgba(150, 200, 255, 0.28);
  border-top-color: #7dd3fc;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}
</style>
