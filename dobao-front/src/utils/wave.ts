/**
 * 深色 hero 的网格波动画（MinIO 登录页那种"丝带"效果）。
 *
 * 与 Vue 组件解耦成纯函数，是为了让 LoginView 只管布局与交互，
 * 并且能在 onUnmounted 时干净地拆掉监听与 rAF。
 */

export interface WaveHandle {
  destroy: () => void
}

export function createWave(canvas: HTMLCanvasElement): WaveHandle {
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return { destroy: () => {} }
  }

  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  let width = 0
  let height = 0
  let rafId: number | null = null
  let startTime = 0

  const LINES = 16 // 横向曲线数量
  const SAMPLES = 170 // 每条曲线的采样点
  const COLUMNS = 26 // 竖向连线数量

  const resize = (): void => {
    const parent = canvas.parentElement
    if (!parent) return
    const rect = parent.getBoundingClientRect()
    // dpr 上限 1.5：4K 屏上按 3 倍建画布会白烧 GPU，这种柔和线条看不出差别
    const dpr = Math.min(window.devicePixelRatio || 1, 1.5)
    width = rect.width
    height = rect.height
    canvas.width = Math.floor(width * dpr)
    canvas.height = Math.floor(height * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }

  /** 某条曲线上 x 处相对中心线的偏移 */
  const offsetAt = (x: number, lineIndex: number, t: number): number => {
    const u = x / width
    // 主波：低频大振幅，决定丝带整体起伏
    const main = Math.sin(u * Math.PI * 2.1 - t * 0.55 + lineIndex * 0.055) * (height * 0.13)
    // 次波：高频小振幅，制造边缘褶皱
    const sub = Math.sin(u * Math.PI * 6.3 + t * 0.85 + lineIndex * 0.12) * (height * 0.045)
    // 左细右散
    const taper = 0.35 + 0.65 * u
    return (main + sub) * taper
  }

  /** 确定性伪随机，避免星点每帧抖动 */
  const hash = (k: number): number => Math.abs(Math.sin(k * 12.9898) * 43758.5453) % 1

  const draw = (t: number): void => {
    ctx.clearRect(0, 0, width, height)

    const cy = height * 0.545
    const half = (LINES - 1) / 2
    const step = height * 0.052

    // 横向曲线
    for (let i = 0; i < LINES; i++) {
      const spread = (i - half) * step
      const depth = 1 - Math.abs(i - half) / (half + 1)
      ctx.beginPath()
      for (let s = 0; s <= SAMPLES; s++) {
        const x = (s / SAMPLES) * width
        const y = cy + spread + offsetAt(x, i, t)
        if (s === 0) ctx.moveTo(x, y)
        else ctx.lineTo(x, y)
      }
      const alpha = 0.05 + depth * 0.42
      ctx.strokeStyle =
        i % 2 === 0
          ? `rgba(150, 226, 255, ${alpha})`
          : `rgba(96, 150, 210, ${alpha * 0.8})`
      ctx.lineWidth = 0.6 + depth * 0.5
      ctx.stroke()
    }

    // 竖向连线
    ctx.strokeStyle = 'rgba(130, 200, 240, 0.11)'
    ctx.lineWidth = 0.55
    for (let c = 0; c <= COLUMNS; c++) {
      const x = (c / COLUMNS) * width
      ctx.beginPath()
      for (let i = 0; i < LINES; i++) {
        const y = cy + (i - half) * step + offsetAt(x, i, t)
        if (i === 0) ctx.moveTo(x, y)
        else ctx.lineTo(x, y)
      }
      ctx.stroke()
    }

    // 星点
    for (let k = 0; k < 46; k++) {
      const x = hash(k) * width
      const y = height * 0.3 + hash(k + 100) * height * 0.42
      const twinkle = 0.25 + 0.55 * Math.abs(Math.sin(t * 0.7 + k))
      ctx.fillStyle = `rgba(255, 255, 255, ${twinkle * 0.5})`
      ctx.fillRect(x, y, 1.4, 1.4)
    }
  }

  const loop = (now: number): void => {
    draw((now - startTime) / 1000)
    rafId = requestAnimationFrame(loop)
  }

  const start = (): void => {
    if (reduceMotion) {
      // 尊重"减少动态效果"：只画静态一帧
      draw(0)
      return
    }
    if (rafId === null) {
      startTime = performance.now()
      rafId = requestAnimationFrame(loop)
    }
  }

  const stop = (): void => {
    if (rafId !== null) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
  }

  const onResize = (): void => {
    resize()
    draw(0)
  }

  // 标签页切到后台时暂停，别在看不见的时候空转
  const onVisibilityChange = (): void => {
    if (document.hidden) stop()
    else start()
  }

  window.addEventListener('resize', onResize)
  document.addEventListener('visibilitychange', onVisibilityChange)

  resize()
  start()

  return {
    destroy: () => {
      stop()
      window.removeEventListener('resize', onResize)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }
}
