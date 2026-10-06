<script setup lang="ts">
/**
 * 品牌标：等距六边形节点网络（智能体"节点"意象）。
 * 登录页气泡用 Linear lavender 渐变；mono 模式给暗色主界面用（纯白）。
 * 渐变 id 用 useId 生成，避免同页多实例时 SVG id 冲突。
 */
import { useId } from 'vue'

const props = withDefaults(defineProps<{ size?: number; mono?: boolean }>(), {
  size: 32,
  mono: false
})

const uid = useId()
const gradId = `brand-grad-${uid}`
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    role="img"
    aria-label="dobao"
  >
    <defs>
      <linearGradient :id="gradId" x1="4" y1="4" x2="20" y2="20" gradientUnits="userSpaceOnUse">
        <stop :stop-color="props.mono ? '#ffffff' : '#5e6ad2'" />
        <stop offset="1" :stop-color="props.mono ? '#d4d4d4' : '#828fff'" />
      </linearGradient>
    </defs>

    <!-- 外六边形：节点网络的骨架 -->
    <path
      :stroke="`url(#${gradId})`"
      stroke-width="1.5"
      stroke-linejoin="round"
      d="M20 12 L16 18.93 L8 18.93 L4 12 L8 5.07 L16 5.07 Z"
    />

    <!-- 中心到三个对角节点的连线 -->
    <g :stroke="`url(#${gradId})`" stroke-width="1.2" stroke-linecap="round">
      <line x1="12" y1="12" x2="16" y2="5.07" />
      <line x1="12" y1="12" x2="4" y2="12" />
      <line x1="12" y1="12" x2="16" y2="18.93" />
    </g>

    <!-- 中心节点 + 三个对角节点 -->
    <circle cx="12" cy="12" r="2.4" :fill="`url(#${gradId})`" />
    <circle cx="16" cy="5.07" r="1.7" :fill="`url(#${gradId})`" />
    <circle cx="4" cy="12" r="1.7" :fill="`url(#${gradId})`" />
    <circle cx="16" cy="18.93" r="1.7" :fill="`url(#${gradId})`" />
  </svg>
</template>
