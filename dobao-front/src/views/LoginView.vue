<script setup lang="ts">
/**
 * 登录页。
 *
 * 视觉遵循 awesome-design-md/design-md/linear.app/DESIGN.md：
 * canvas #010102 近纯黑底 + hairline 细网格 + 单一 lavender 强调色；
 * 右侧悬浮玻璃气泡承载品牌标与项目名，下方依次是 GitHub 登录按钮与仓库链接；
 * 左侧是产品介绍。玻璃拟态只做无彩色（半透明表面 + backdrop-blur + 1px 描边 + 顶边高光），
 * 不引入第二个彩色。鉴权完全交给 GitHub，本站不保存任何密码。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { AuthConfig } from '@/api/auth'
import { authBase } from '@/config'
import { useAuth } from '@/stores/auth'
import { createWave, type WaveHandle } from '@/utils/wave'
import BrandMark from '@/components/BrandMark.vue'

const REPO_URL = 'https://github.com/bear2-tangwang/dobao-agent'

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
  <div class="login-page">
    <div class="login-layout">
      <!-- ==================== 科技玻璃拟态背景层 ==================== -->
      <div class="bg-grid" aria-hidden="true"></div>
      <div class="bg-aurora" aria-hidden="true"></div>
      <canvas ref="heroCanvas" class="bg-wave" aria-hidden="true"></canvas>

      <!-- ==================== 左：项目介绍 ==================== -->
      <section class="intro">
        <p class="eyebrow">DOBAO · OPEN SOURCE</p>
        <h1 class="intro-title">高性能 AI 智能体平台</h1>
        <p class="intro-desc">
          dobao-agent 是一个端到端通用智能体平台。对话问答、联网搜索、<b>PPT 智能生成</b>与<b>面试总结</b>
          在同一个工作台里完成，长任务以流式进度实时回传。
        </p>
        <p class="intro-desc second">
          登录后，你的会话、上传的文件、生成的 PPT 与面试报告都只属于你自己。
          我们仅通过 GitHub 校验身份，<b>不保存任何密码</b>。
        </p>
        <div class="intro-tags">
          <span>流式对话</span>
          <span>联网搜索</span>
          <span>PPT 生成</span>
          <span>面试总结</span>
          <span>数据隔离</span>
        </div>
      </section>

      <!-- ==================== 右：悬浮气泡 + 登录动作 ==================== -->
      <section class="side">
        <div class="orb">
          <BrandMark :size="64" class="orb-mark" />
          <div class="orb-name">dobao通用智能体平台</div>
        </div>

        <div class="actions">
          <div v-if="errorText" class="alert">{{ errorText }}</div>

          <button
            class="btn btn-primary"
            type="button"
            :disabled="submitting || !loginEnabled || !!hostMismatch"
            @click="handleLogin"
          >
            <span v-if="submitting" class="spinner" aria-hidden="true"></span>
            <svg
              v-else
              width="18"
              height="18"
              viewBox="0 0 16 16"
              fill="currentColor"
              aria-hidden="true"
            >
              <path
                d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"
              />
            </svg>
            <span>{{ submitting ? '正在跳转 GitHub…' : '使用 GitHub 登录' }}</span>
          </button>

          <a class="btn btn-secondary" :href="REPO_URL" target="_blank" rel="noopener noreferrer">
            <svg width="18" height="18" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
              <path
                d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"
              />
            </svg>
            <span>查看 GitHub 仓库</span>
          </a>

          <p class="form-hint">将跳转至 github.com 完成授权，授权后自动返回本站点</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ============ Linear token（linear.app/DESIGN.md） ============ */
.login-page {
  --canvas: #010102;
  --surface-1: #0f1011;
  --surface-2: #141516;
  --surface-3: #18191a;
  --hairline: #23252a;
  --hairline-strong: #34343a;
  --primary: #5e6ad2;
  --primary-hover: #828fff;
  --primary-focus: #5e69d1;
  --ink: #f7f8f8;
  --ink-muted: #d0d6e0;
  --ink-subtle: #8a8f98;
  --ink-tertiary: #62666d;
  --danger: #e5484d;

  width: 100%;
  height: 100vh;
  overflow-y: auto;
  background: var(--canvas);
  color: var(--ink);
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', Roboto, Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
}

.login-layout {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 480px;
  min-height: 100vh;
}

/* ==================== 背景层：网格 + 单光源 + 网格波 ==================== */
.bg-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(to bottom, rgba(35, 37, 42, 0.75) 1px, transparent 1px),
    linear-gradient(to right, rgba(35, 37, 42, 0.75) 1px, transparent 1px);
  background-size: 64px 64px;
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 40% 50%, #000 30%, transparent 88%);
  mask-image: radial-gradient(ellipse 80% 70% at 40% 50%, #000 30%, transparent 88%);
}

/* Linear 只允许一个彩色光源：lavender 径向光，压到极低饱和 */
.bg-aurora {
  position: absolute;
  width: 900px;
  height: 900px;
  top: -320px;
  left: -240px;
  pointer-events: none;
  background: radial-gradient(circle, rgba(94, 106, 210, 0.1) 0%, transparent 68%);
}

.bg-wave {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  opacity: 0.3;
}

/* ==================== 左：项目介绍 ==================== */
.intro {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(48px, 6vw, 120px) clamp(40px, 6vw, 120px);
}

.eyebrow {
  margin: 0 0 22px;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.3;
  letter-spacing: 0.4px;
  color: var(--primary-hover);
}

.intro-title {
  margin: 0 0 30px;
  font-size: clamp(32px, 3.2vw, 40px);
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -1px;
  color: var(--ink);
}

.intro-desc {
  margin: 0;
  max-width: 640px;
  font-size: 18px;
  line-height: 1.7;
  letter-spacing: -0.1px;
  color: var(--ink-muted);
}
.intro-desc.second {
  margin-top: 18px;
}
.intro-desc b {
  color: var(--ink);
  font-weight: 600;
}

.intro-tags {
  margin-top: 36px;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
/* status-badge 规格：surface-2 + pill + hairline */
.intro-tags span {
  font-size: 12px;
  line-height: 1.4;
  padding: 3px 10px;
  border-radius: 9999px;
  color: var(--ink-subtle);
  background: var(--surface-2);
  border: 1px solid var(--hairline);
}

/* ==================== 右：悬浮玻璃气泡 + 动作区 ==================== */
.side {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 40px;
  padding: clamp(48px, 5vw, 80px) clamp(28px, 3vw, 56px);
}

/* 悬浮大气泡：无彩色玻璃（半透明表面 + backdrop-blur + 1px 描边 + 顶边高光） */
.orb {
  width: clamp(260px, 24vw, 360px);
  aspect-ratio: 1;
  border-radius: 50%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  background: radial-gradient(
    circle at 32% 26%,
    rgba(255, 255, 255, 0.1) 0%,
    rgba(255, 255, 255, 0.03) 34%,
    rgba(15, 16, 17, 0.55) 68%
  );
  backdrop-filter: blur(24px) saturate(120%);
  -webkit-backdrop-filter: blur(24px) saturate(120%);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.18),
    0 24px 64px rgba(0, 0, 0, 0.45),
    0 0 0 14px rgba(94, 106, 210, 0.05);
  animation: orbFloat 6s ease-in-out infinite;
}

@keyframes orbFloat {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-10px);
  }
}

.orb-name {
  max-width: 76%;
  text-align: center;
  font-size: 22px;
  font-weight: 500;
  line-height: 1.25;
  letter-spacing: -0.4px;
  color: var(--ink);
}

.actions {
  width: 100%;
  max-width: 340px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.alert {
  padding: 10px 13px;
  font-size: 13px;
  line-height: 1.6;
  color: #ff9b9b;
  background: rgba(229, 72, 77, 0.12);
  border: 1px solid rgba(229, 72, 77, 0.35);
  border-radius: 8px;
}

/* Linear button-primary：lavender，8px 圆角，不做胶囊 */
.btn {
  width: 100%;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.2;
  font-family: inherit;
  border-radius: 8px;
  text-decoration: none;
  cursor: pointer;
  transition:
    background 0.18s ease,
    border-color 0.18s ease,
    transform 0.12s ease;
}

.btn:focus-visible {
  outline: 2px solid rgba(94, 105, 209, 0.5);
  outline-offset: 2px;
}

.btn-primary {
  color: #fff;
  background: var(--primary);
  border: none;
}
.btn-primary:hover:not(:disabled) {
  background: var(--primary-hover);
}
.btn-primary:active:not(:disabled) {
  background: var(--primary-focus);
  transform: translateY(1px);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: default;
}

/* Linear button-secondary 的玻璃化：surface + hairline 描边 */
.btn-secondary {
  color: var(--ink);
  background: rgba(20, 21, 22, 0.6);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--hairline);
}
.btn-secondary:hover {
  background: var(--surface-3);
  border-color: var(--hairline-strong);
}
.btn-secondary:active {
  transform: translateY(1px);
}

.spinner {
  width: 16px;
  height: 16px;
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
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-tertiary);
  text-align: center;
}

/* ==================== 窄屏：气泡与动作在上，介绍在下 ==================== */
@media (max-width: 980px) {
  .login-layout {
    grid-template-columns: 1fr;
  }
  .side {
    order: 1;
    gap: 32px;
    padding-top: 56px;
    padding-bottom: 24px;
  }
  .intro {
    order: 2;
    padding-top: 24px;
    padding-bottom: 64px;
  }
  .orb {
    width: 200px;
    gap: 14px;
  }
  .orb-mark {
    width: 48px;
    height: 48px;
  }
  .orb-name {
    font-size: 18px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .orb {
    animation: none;
  }
  .spinner {
    animation: none;
  }
}
</style>
