<script setup lang="ts">
/**
 * 登录页。
 *
 * 视觉对齐 MinIO 控制台：左 62% 深色 hero（网格波 + 产品介绍），右 38% 白底登录栏。
 * 差异只有一处 —— 表单区不是"用户名 + 密码"，而是一个「使用 GitHub 登录」按钮，
 * 因为鉴权完全交给 GitHub，本站不保存任何密码。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { AuthConfig } from '@/api/auth'
import { authBase } from '@/config'
import { useAuth } from '@/stores/auth'
import { createWave, type WaveHandle } from '@/utils/wave'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const heroCanvas = ref<HTMLCanvasElement | null>(null)
const submitting = ref(false)
const errorFromQuery = ref<string | null>(null)
const loginEnabled = ref(true)

/** 配置未加载完时为 null，表示"还判断不了" */
const authConfig = ref<AuthConfig | null>(null)

/** 登录成功后的落点：只接受站内相对路径 */
const redirectTarget = computed(() => {
  const raw = route.query.redirect
  const value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//')) {
    return value
  }
  return '/'
})

let wave: WaveHandle | null = null

/** 从 URL 里取主机名；解析失败返回 null（该项跳过校验） */
const hostOf = (url: string | null | undefined): string | null => {
  if (!url) return null
  try {
    return new URL(url).hostname
  } catch {
    return null
  }
}

/**
 * 主机名一致性检查 —— **以服务端配置为准**，不在前端写死主机名。
 *
 * <p>会话 Cookie 按**主机名**隔离（端口不影响），链路上三个主机名必须完全相同，
 * 否则 state 会"写在一个站点、另一个站点读不到"，表现为 ?error=state：
 * `authBase`（authorize 请求与 Cookie 落点）、后端 `github.oauth.redirect-uri`
 * （GitHub 回跳地址）、当前页面主机（/auth/me 走同源相对路径，登录态最终在这里读到）。
 */
const hostMismatch = computed(() => {
  const config = authConfig.value
  if (typeof window === 'undefined' || !config) return null
  const current = window.location.hostname
  const authorize = hostOf(authBase)
  const callback = config.redirectUriHost
  const landing = hostOf(config.frontendBaseUrl)

  // ① Cookie 写在 authorize 主机上，回跳却到另一个主机 → 回调读到的会话是新建的空会话
  if (authorize && callback && authorize !== callback) {
    return `授权入口配的是 ${authorize}，GitHub 回跳是 ${callback}：`
      + `会话 Cookie 按主机名隔离（端口不影响），回跳请求带不上它，state 校验必然失败。`
      + `请让 VITE_AUTH_BASE 与后端 github.oauth.redirect-uri 用同一个主机（${callback}）。`
  }
  // ② 回跳主机与登录后落地主机不同 → 回调里写好的登录态在落地页面上同样读不到
  if (callback && landing && callback !== landing) {
    return `后端 github.oauth.redirect-uri 的主机是 ${callback}，`
      + `frontend-base-url 的主机却是 ${landing}，登录态会丢在另一个站点上。`
  }
  // ③ 页面主机与回跳（或落地）主机不同 → /auth/me 走同源路径，一样带不上 Cookie
  const expected = callback ?? landing
  if (expected && current !== expected) {
    return `当前用 ${current} 打开，但服务端配置的登录地址是 ${expected} —— `
      + `会话 Cookie 按主机名隔离，两者不通用，登录后会回跳失败。`
      + `请改用 http://${expected}:${window.location.port} 访问。`
  }
  return null
})

/** 页面展示的提示：主机名问题优先于 URL 带回来的 error */
const errorText = computed(() => hostMismatch.value ?? errorFromQuery.value)

onMounted(async () => {
  if (heroCanvas.value) {
    wave = createWave(heroCanvas.value)
  }

  // 回跳失败时后端会带 ?error=xxx 过来（见 stores/auth 的错误映射表）
  const raw = route.query.error
  errorFromQuery.value = auth.messageForError(Array.isArray(raw) ? raw[0] : raw)

  // 已经登录的人不该看到登录页
  try {
    if (await auth.fetchMe(true)) {
      await router.replace(redirectTarget.value)
      return
    }
  } catch {
    // 后端不可达时照常展示登录页，点按钮时会再暴露问题
  }

  try {
    const config = await auth.loadConfig()
    loginEnabled.value = config.enabled
    // 整份配置交给 hostMismatch 计算属性：它要同时比对 authorize / redirect-uri / 页面三个主机名
    authConfig.value = config
    if (!config.enabled) {
      errorFromQuery.value = '服务端尚未配置 GitHub OAuth（client-id / client-secret），暂时无法登录。'
    }
  } catch {
    // 配置接口失败不阻塞页面渲染（此时跳过主机名校验）
  }
})

onBeforeUnmount(() => {
  wave?.destroy()
})

const handleLogin = (): void => {
  // 主机名不一致时按钮本就被置灰，这里再兜一层：放行的话会绕一圈回来报
  // "登录状态已过期"，那种症状比一句明确的提示难排查得多
  if (hostMismatch.value) {
    return
  }
  submitting.value = true
  // 整页跳转到后端授权入口，由后端 302 到 GitHub
  auth.login(redirectTarget.value)
}
</script>

<template>
  <div class="login-layout">
    <!-- ==================== 左：品牌介绍 + 网格波 ==================== -->
    <section class="login-hero">
      <canvas ref="heroCanvas" class="hero-canvas"></canvas>
      <div class="hero-content">
        <h1 class="hero-title">高性能 AI 智能体平台</h1>
        <p class="hero-desc">
          dobao-agent 是一个端到端通用智能体平台。对话问答、联网搜索、<b>PPT 智能生成</b>与<b>面试总结</b>
          在同一个工作台里完成，长任务以流式进度实时回传。
        </p>
        <p class="hero-desc second">
          登录后，你的会话、上传的文件、生成的 PPT 与面试报告都只属于你自己。
          我们仅通过 GitHub 校验身份，<b>不保存任何密码</b>。
        </p>
        <div class="hero-tags">
          <span>流式对话</span>
          <span>联网搜索</span>
          <span>PPT 生成</span>
          <span>面试总结</span>
          <span>数据隔离</span>
        </div>
      </div>
    </section>

    <!-- ==================== 右：登录面板 ==================== -->
    <section class="login-panel">
      <div class="brand">
        <div class="brand-mark">DOBAO</div>
        <div class="brand-name">AGENT STUDIO</div>
        <div class="brand-edition">Community Edition</div>
      </div>

      <div class="login-form">
        <div v-if="errorText" class="alert">{{ errorText }}</div>

        <button
          class="gh-btn"
          type="button"
          :disabled="submitting || !loginEnabled || !!hostMismatch"
          @click="handleLogin"
        >
          <span v-if="submitting" class="spinner" aria-hidden="true"></span>
          <svg
            v-else
            width="20"
            height="20"
            viewBox="0 0 16 16"
            fill="#fff"
            aria-hidden="true"
          >
            <path
              d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"
            />
          </svg>
          <span>{{ submitting ? '正在跳转 GitHub…' : '使用 GitHub 登录' }}</span>
        </button>

        <p class="form-hint">将跳转至 github.com 完成授权，授权后自动返回本站点</p>
      </div>

      <div class="panel-spacer"></div>

      <div class="panel-footer">
        <a href="#" @click.prevent>文档</a><i>|</i><a href="#" @click.prevent>GitHub</a><i>|</i
        ><a href="#" @click.prevent>支持</a>
      </div>
    </section>
  </div>
</template>

<style scoped>
:root {
  --primary-dark: #6d28d9;
  --gh-btn: #24292f;
  --gh-btn-hover: #32383f;
}

.login-layout {
  display: grid;
  grid-template-columns: 1.62fr 1fr;
  min-height: 100vh;
}

/* ============ 左：深色 hero ============ */
.login-hero {
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, #0a111f 0%, #16233a 55%, #0d1a2e 100%);
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.login-hero::before,
.login-hero::after {
  content: '';
  position: absolute;
  border-radius: 50%;
  pointer-events: none;
}
.login-hero::before {
  width: 720px;
  height: 720px;
  top: -260px;
  left: -160px;
  background: radial-gradient(circle, rgba(139, 92, 246, 0.16) 0%, transparent 68%);
}
.login-hero::after {
  width: 640px;
  height: 640px;
  bottom: -240px;
  right: -120px;
  background: radial-gradient(circle, rgba(6, 182, 212, 0.14) 0%, transparent 70%);
}

.hero-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.hero-content {
  position: relative;
  z-index: 2;
  padding: 0 clamp(48px, 7vw, 190px) 90px;
  max-width: 900px;
}

.hero-title {
  margin: 0 0 34px;
  font-size: clamp(26px, 2.2vw, 36px);
  font-weight: 700;
  letter-spacing: 2px;
  color: #fff;
}

.hero-desc {
  margin: 0;
  max-width: 660px;
  font-size: 15px;
  line-height: 2.05;
  letter-spacing: 0.3px;
  color: rgba(203, 216, 235, 0.82);
}
.hero-desc.second {
  margin-top: 18px;
}
.hero-desc b {
  color: #e8eefb;
  font-weight: 600;
}

.hero-tags {
  margin-top: 38px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.hero-tags span {
  font-size: 12.5px;
  padding: 5px 12px;
  border-radius: 999px;
  color: rgba(200, 220, 245, 0.9);
  border: 1px solid rgba(120, 160, 220, 0.28);
  background: rgba(120, 160, 220, 0.08);
}

/* ============ 右：白底登录栏 ============ */
.login-panel {
  background: #fff;
  display: flex;
  flex-direction: column;
  padding: 56px clamp(32px, 3.4vw, 64px) 40px;
}

.brand {
  text-align: center;
  margin-bottom: 78px;
}
.brand-mark {
  font-size: 27px;
  font-weight: 800;
  letter-spacing: 3px;
  color: #c0392b;
  line-height: 1;
}
.brand-name {
  margin-top: 6px;
  font-size: 33px;
  font-weight: 700;
  letter-spacing: 1.5px;
  color: #1f2a37;
  line-height: 1.1;
}
.brand-edition {
  display: inline-block;
  margin-top: 14px;
  padding: 3px 12px;
  font-size: 12.5px;
  color: #475569;
  border: 1px solid #cbd5e1;
}

.login-form {
  width: 100%;
  max-width: 340px;
  margin: 0 auto;
}

.alert {
  margin-bottom: 14px;
  padding: 10px 13px;
  font-size: 13px;
  line-height: 1.6;
  color: #b42318;
  background: #fef3f2;
  border: 1px solid #fecdca;
  border-radius: 4px;
}

.gh-btn {
  width: 100%;
  height: 54px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 11px;
  font-size: 15.5px;
  font-weight: 500;
  color: #fff;
  background: #24292f;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  transition:
    background 0.18s ease,
    transform 0.12s ease;
  font-family: inherit;
}
.gh-btn:hover:not(:disabled) {
  background: #32383f;
}
.gh-btn:active:not(:disabled) {
  transform: translateY(1px);
}
.gh-btn:disabled {
  opacity: 0.72;
  cursor: default;
}

.spinner {
  width: 17px;
  height: 17px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.form-hint {
  margin: 14px 0 0;
  font-size: 12.5px;
  line-height: 1.75;
  color: #8b96a5;
  text-align: center;
}

.panel-spacer {
  flex: 1 1 auto;
  min-height: 40px;
}

.panel-footer {
  border-top: 1px solid #e5e7eb;
  padding-top: 22px;
  text-align: center;
  font-size: 13px;
}
.panel-footer a {
  color: #4b5563;
  text-decoration: none;
  padding: 0 10px;
}
.panel-footer a:hover {
  color: #6d28d9;
  text-decoration: underline;
}
.panel-footer i {
  color: #d1d5db;
  font-style: normal;
}

/* ============ 窄屏：隐藏 hero，只留登录栏 ============ */
@media (max-width: 980px) {
  .login-layout {
    grid-template-columns: 1fr;
  }
  .login-hero {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation: none;
  }
}
</style>
