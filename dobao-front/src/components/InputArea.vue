<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import type { Agent } from '@/types'
import { formatFileSize } from '@/utils/format'

const props = defineProps<{
  agents: Agent[]
  selectedAgent: string
  selectedFile: File | null
  isUploading: boolean
  inputMessage: string
  canSend: boolean
  isSending: boolean
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

defineExpose({ focusInput })
</script>

<template>
  <div class="input-area">
    <!-- 智能体选择器 -->
    <div class="agent-selector">
      <div
        v-for="agent in agents"
        :key="agent.id"
        :class="['agent-item', { active: selectedAgent === agent.id }]"
        @click="$emit('select-agent', agent.id)"
      >
        <span class="agent-icon">{{ agent.icon }}</span>
        <span class="agent-name">{{ agent.name }}</span>
        <i v-if="selectedAgent === agent.id" class="fas fa-check check-icon"></i>
      </div>
    </div>

    <!-- 文件预览区域 -->
    <div v-if="selectedFile" class="file-preview">
      <div class="file-preview-item">
        <div class="file-icon-wrapper">
          <i class="fas fa-file file-icon"></i>
        </div>
        <div class="file-info">
          <div class="file-name">{{ selectedFile.name }}</div>
          <div class="file-size">{{ formatFileSize(selectedFile.size) }}</div>
          <div v-if="isUploading" class="upload-parsing">
            <i class="fas fa-spinner fa-spin"></i>
            <span>解析中...</span>
          </div>
        </div>
        <div class="file-actions">
          <button v-if="!isUploading" class="remove-file" @click="$emit('remove-file')" title="删除文件">
            <i class="fas fa-trash-alt"></i>
          </button>
        </div>
      </div>
    </div>

    <!-- 输入容器 -->
    <div class="input-container">
      <!-- 文件上传按钮（仅文件问答模式可用） -->
      <button
        v-if="selectedAgent === 'file' && !selectedFile"
        class="file-btn"
        :class="{ disabled: isUploading }"
        :disabled="isUploading"
        @click="fileInput?.click()"
        title="上传文件（限1个）"
      >
        <i class="fas fa-paperclip"></i>
      </button>
      <input ref="fileInput" type="file" @change="$emit('handle-file-select', $event)" style="display: none;" />

      <!-- 文件图标（文件问答模式显示） -->
      <div v-if="selectedFile && !isUploading" class="input-file-icon" title="文件问答模式">
        <i class="fas fa-file-alt"></i>
      </div>

      <!-- 输入框 -->
      <textarea
        v-model="inputModel"
        :placeholder="selectedAgent === 'file' && selectedFile ? '文件问答模式... (删除文件可切换回对话助手)' : '输入消息... (支持 Markdown，Shift+Enter 换行)'"
        @keydown.enter.exact.prevent="$emit('send')"
        @keydown.enter.shift.exact="inputModel += '\n'"
        rows="1"
        ref="textareaInput"
      ></textarea>

      <!-- 发送/停止按钮 -->
      <button
        :class="['send-btn', { stop: isSending, disabled: !isSending && (!canSend || isUploading) }]"
        @click="isSending ? $emit('stop') : $emit('send')"
        :disabled="isSending ? false : (!canSend || isUploading)"
      >
        <i v-if="isSending" class="fas fa-stop"></i>
        <i v-else class="fas fa-paper-plane"></i>
      </button>
    </div>
  </div>
</template>