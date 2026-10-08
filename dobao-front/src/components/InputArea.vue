<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import type { Agent } from '@/types'
import { formatFileSize } from '@/utils/format'
import { AUDIO_EXTENSIONS } from '@/utils/constants'

const props = defineProps<{
  agents: Agent[]
  selectedAgent: string
  selectedFile: File | null
  isUploading: boolean
  inputMessage: string
  canSend: boolean
  isSending: boolean
  /** 面试模式：选中的录音（还没提交） */
  interviewFile?: File | null
}>()

const emit = defineEmits<{
  (e: 'update:inputMessage', value: string): void
  (e: 'select-agent', agentId: string): void
  (e: 'handle-file-select', event: Event): void
  (e: 'remove-file'): void
  (e: 'send'): void
  (e: 'stop'): void
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const textareaInput = ref<HTMLTextAreaElement | null>(null)

/** 面试模式下文件选择器只收音频 */
const acceptAttr = computed(() =>
  props.selectedAgent === 'interview' ? AUDIO_EXTENSIONS.map(ext => '.' + ext).join(',') : undefined
)

const inputModel = computed({
  get: () => props.inputMessage,
  set: (value: string) => emit('update:inputMessage', value)
})

// textarea 高度自适应
watch(
  () => props.inputMessage,
  () => {
    nextTick(() => {
      if (textareaInput.value) {
        textareaInput.value.style.height = 'auto'
        textareaInput.value.style.height = textareaInput.value.scrollHeight + 'px'
      }
    })
  }
)

// 供父组件在快捷提问后聚焦输入框
const focusInput = () => {
  textareaInput.value?.focus()
}

/** 面试模式：点工具栏的录音图标打开选择器 */
const openFilePicker = () => {
  fileInput.value?.click()
}

defineExpose({ focusInput, openFilePicker })
</script>

<template>
  <div class="input-area">
    <!-- 豆包风格单卡片输入框 -->
    <div class="input-card">
      <!-- 面试模式：录音 chip（与普通文件 chip 同位置同观感） -->
      <div v-if="selectedAgent === 'interview' && interviewFile" class="file-chip-row">
        <div class="file-chip">
          <div class="chip-icon">
            <i class="fa-solid fa-file-audio"></i>
          </div>
          <div class="chip-meta">
            <span class="chip-name">{{ interviewFile.name }}</span>
            <span class="chip-size">{{ formatFileSize(interviewFile.size) }} · 音频</span>
          </div>
          <div v-if="isSending" class="chip-parsing">
            <i class="fas fa-spinner fa-spin"></i>
            <span>提交中...</span>
          </div>
          <button v-else class="chip-remove" title="删除文件" @click="$emit('remove-file')">
            <i class="fas fa-times"></i>
          </button>
        </div>
      </div>

      <!-- 文件 chip（上传后显示） -->
      <div v-else-if="selectedFile" class="file-chip-row">
        <div class="file-chip">
          <div class="chip-icon">
            <i class="fas fa-file"></i>
          </div>
          <div class="chip-meta">
            <span class="chip-name">{{ selectedFile.name }}</span>
            <span class="chip-size">{{ formatFileSize(selectedFile.size) }}</span>
          </div>
          <div v-if="isUploading" class="chip-parsing">
            <i class="fas fa-spinner fa-spin"></i>
            <span>解析中...</span>
          </div>
          <button v-if="!isUploading" class="chip-remove" title="删除文件" @click="$emit('remove-file')">
            <i class="fas fa-times"></i>
          </button>
        </div>
      </div>

      <!-- 文本输入区；面试模式不输入文字，换成一句引导 -->
      <div v-if="selectedAgent === 'interview'" class="interview-input-notice">
        <i class="fa-solid fa-circle-info"></i>
        <span>
          {{ interviewFile ? '确认无误后点右侧按钮开始总结' : '请选择一段面试录音（mp3 / wav / m4a / aac / flac / amr）' }}
        </span>
      </div>
      <textarea
        v-else
        v-model="inputModel"
        :placeholder="selectedAgent === 'chat' && selectedFile ? '已上传文件，可文件问答 + 联网搜索 (如需普通对话请删除文件)' : '输入消息... (支持 Markdown，Shift+Enter 换行)'"
        @keydown.enter.exact.prevent="$emit('send')"
        @keydown.enter.shift.exact="inputModel += '\n'"
        rows="1"
        ref="textareaInput"
      ></textarea>

      <!-- 底部工具栏 -->
      <div class="input-toolbar">
        <!-- 上传按钮：对话模式（回形针）与面试模式（录音）共用 -->
        <button
          v-if="(selectedAgent === 'chat' && !selectedFile) || (selectedAgent === 'interview' && !interviewFile)"
          class="toolbar-upload"
          :class="{ disabled: isUploading }"
          :disabled="isUploading"
          :title="selectedAgent === 'interview' ? '选择面试录音（限1个）' : '上传文件（限1个）'"
          @click="openFilePicker"
        >
          <i :class="selectedAgent === 'interview' ? 'fa-solid fa-microphone-lines' : 'fas fa-paperclip'"></i>
        </button>
        <input
          ref="fileInput"
          type="file"
          style="display: none;"
          :accept="acceptAttr"
          @change="$emit('handle-file-select', $event)"
        />

        <!-- 智能体功能入口（卡片内、可横向滚动） -->
        <div class="toolbar-agents">
          <div
            v-for="agent in agents"
            :key="agent.id"
            :class="['toolbar-agent', { active: selectedAgent === agent.id }]"
            @click="$emit('select-agent', agent.id)"
          >
            <i :class="agent.icon"></i>
            <span class="toolbar-agent-name">{{ agent.name }}</span>
          </div>
        </div>

        <!-- 发送 / 停止按钮 -->
        <button
          :class="['send-btn', { stop: isSending, disabled: !isSending && (!canSend || isUploading) }]"
          @click="isSending ? $emit('stop') : $emit('send')"
          :disabled="isSending ? false : (!canSend || isUploading)"
        >
          <i v-if="isSending" class="fas fa-stop"></i>
          <i v-else-if="selectedAgent === 'interview'" class="fa-solid fa-wand-magic-sparkles"></i>
          <i v-else class="fas fa-paper-plane"></i>
        </button>
      </div>
    </div>
  </div>
</template>
