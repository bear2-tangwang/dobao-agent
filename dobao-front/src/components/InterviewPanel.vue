<script setup lang="ts">
import { computed } from 'vue'
import type { InterviewSession, InterviewStepKey } from '@/types'
import { AUDIO_EXTENSIONS, MAX_AUDIO_BYTES } from '@/utils/constants'
import { formatFileSize } from '@/utils/format'

const props = defineProps<{
  session: InterviewSession
  processing: boolean
  loading: boolean
  agreed: boolean
  /** 已选中但还没提交的录音（显示"开始总结"按钮） */
  pendingFileName: string | null
}>()

const emit = defineEmits<{
  (e: 'toggle-agree'): void
  (e: 'pick-file'): void
  (e: 'start'): void
  (e: 'retry'): void
  (e: 'refresh-report'): void
}>()

const busyText = computed(() => {
  if (!props.processing) {
    return ''
  }
  return '处理中，通常需要 2~8 分钟，请勿关闭页面'
})

/** 每个阶段配一个图标；完成打勾，当前阶段转圈 */
const STEP_ICON: Record<InterviewStepKey, string> = {
  uploaded: 'fa-solid fa-file-audio',
  transcribing: 'fa-solid fa-language',
  transcribed: 'fa-solid fa-file-lines',
  analyzing: 'fa-solid fa-user-tie',
  extracting: 'fa-solid fa-list-check',
  reporting: 'fa-solid fa-pen-ruler',
  ready: 'fa-solid fa-circle-check',
  error: 'fa-solid fa-circle-exclamation'
}

const stepIcon = (key: InterviewStepKey): string => STEP_ICON[key] || 'fa-solid fa-circle-notch'

const isLastStep = (index: number): boolean => index === props.session.steps.length - 1
</script>

<template>
  <div class="interview-panel">
    <!-- 上传中：还没有 interviewId，此时不能露出上传卡（卡上的按钮会打断正在跑的场次） -->
    <div v-if="!session.interviewId && session.uploading" class="interview-uploading">
      <i class="fas fa-spinner fa-spin"></i>
      <span class="interview-uploading-text">正在上传《{{ session.fileName || '录音' }}》，请勿关闭页面…</span>
    </div>

    <!-- 上传区：还没开始处理时显示 -->
    <template v-else-if="!session.interviewId">
      <div class="interview-upload">
        <div class="interview-upload-icon">
          <i class="fa-solid fa-microphone-lines"></i>
        </div>
        <div class="interview-upload-title">上传面试录音，自动生成总结报告</div>
        <div class="interview-upload-desc">
          支持 {{ AUDIO_EXTENSIONS.join(' / ') }}，单文件不超过 {{ formatFileSize(MAX_AUDIO_BYTES) }}、时长不超过 1.5 小时。<br />
          系统会自动转写、区分面试官与候选人，并按对话轮次逐条整理成问答清单（均为逐字原文），产出「问答清单 / 知识点清单 / 待补充知识点」。
        </div>

        <!-- 已选文件（还没提交） -->
        <div v-if="pendingFileName" class="interview-pending">
          <i class="fa-solid fa-file-audio"></i>
          <span class="interview-pending-name">{{ pendingFileName }}</span>
          <button class="interview-change-btn" type="button" @click="$emit('pick-file')">重新选择</button>
        </div>

        <label class="interview-consent" :class="{ checked: agreed }">
          <input type="checkbox" :checked="agreed" @change="$emit('toggle-agree')" />
          <span>我已获得录音中各方的同意，确认可以上传并分析这段录音</span>
        </label>

        <button
          v-if="!pendingFileName"
          class="interview-pick-btn"
          type="button"
          :disabled="loading"
          @click="$emit('pick-file')"
        >
          <i v-if="loading" class="fas fa-spinner fa-spin"></i>
          <i v-else class="fa-solid fa-upload"></i>
          <span>{{ loading ? '正在提交…' : '选择录音文件' }}</span>
        </button>
        <button
          v-else
          class="interview-pick-btn primary"
          type="button"
          :disabled="!agreed || loading"
          @click="$emit('start')"
        >
          <i v-if="loading" class="fas fa-spinner fa-spin"></i>
          <i v-else class="fa-solid fa-wand-magic-sparkles"></i>
          <span>{{ loading ? '正在提交…' : '开始总结' }}</span>
        </button>
        <div v-if="!agreed" class="interview-consent-hint">请先勾选上方确认项</div>
      </div>
    </template>

    <!-- 进度区 -->
    <template v-else>
      <div class="interview-progress">
        <div class="interview-progress-head">
          <i class="fa-solid fa-file-audio"></i>
          <span class="interview-file-name">{{ session.fileName || '面试录音' }}</span>
          <span v-if="session.fileSize" class="interview-file-size">{{ formatFileSize(session.fileSize) }}</span>
        </div>

        <ul class="interview-steps">
          <li
            v-for="(step, index) in session.steps"
            :key="step.key"
            class="interview-step"
            :class="{ done: step.done, active: processing && isLastStep(index) && step.key !== 'error', error: step.key === 'error' }"
          >
            <span class="interview-step-icon">
              <i v-if="step.done" class="fa-solid fa-check"></i>
              <i v-else-if="processing && isLastStep(index)" class="fas fa-spinner fa-spin"></i>
              <i v-else :class="stepIcon(step.key)"></i>
            </span>
            <span class="interview-step-text">{{ step.text }}</span>
          </li>
        </ul>

        <div v-if="processing" class="interview-tip">
          <i class="fas fa-spinner fa-spin"></i>
          <span>{{ busyText }}</span>
        </div>

        <div v-if="session.degraded && processing" class="interview-warn">
          <i class="fa-solid fa-triangle-exclamation"></i>
          <span>实时进度连接已断开，已切换为定时查询（任务仍在后台运行，进度不会丢失）</span>
        </div>

        <div v-if="session.stopped" class="interview-warn">
          <i class="fa-solid fa-circle-pause"></i>
          <span>已停止接收进度（后端任务仍在运行，可稍后点"载入报告内容"查看结果）</span>
        </div>

        <div v-if="session.errorMsg" class="interview-error">
          <div class="interview-error-text">
            <i class="fa-solid fa-circle-exclamation"></i>
            <span>{{ session.errorMsg }}</span>
          </div>
          <button class="interview-retry-btn" type="button" @click="$emit('retry')">
            <i class="fa-solid fa-rotate-right"></i>
            <span>重试</span>
          </button>
        </div>

        <!-- 没在跑又还没拿到报告：给一个手动拉取入口（停过 / 断过 / 只拿到 reportUrl） -->
        <div v-if="session.interviewId && !session.report && !processing" class="interview-report-actions">
          <button class="interview-report-btn" type="button" :disabled="loading" @click="$emit('refresh-report')">
            <i v-if="loading" class="fas fa-spinner fa-spin"></i>
            <i v-else class="fa-solid fa-file-lines"></i>
            <span>载入报告内容</span>
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
