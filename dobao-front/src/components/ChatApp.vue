<script setup lang="ts">
/**
 * 主界面。
 *
 * 独立于 App.vue（后者只放 <RouterView>），这样登录页与回跳页
 * 不需要把整套聊天状态一起挂载起来。
 */
import { ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useChat } from '@/composables/useChat'
import { useAuth } from '@/stores/auth'
import SideBar from '@/components/SideBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import MessageItem from '@/components/MessageItem.vue'
import InputArea from '@/components/InputArea.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ConnectionError from '@/components/ConnectionError.vue'

const router = useRouter()
const auth = useAuth()

const {
  connectionError,
  agents,
  selectedAgent,
  chatList,
  currentChatId,
  currentChat,
  inputMessage,
  selectedFile,
  isUploading,
  isSending,
  canSend,
  messagesContainer,
  selectAgent,
  quickPrompt,
  sendRecommendQuestion,
  isLastMessage,
  createNewChat,
  selectChat,
  deleteChat,
  removeFile,
  handleFileSelect,
  handleFiles,
  sendMessage,
  stopMessage,
  toggleThinking,
  toggleReference,
  copyMessage,
  testConnection,
  showConfirmDialog,
  confirmTitle,
  confirmMessage,
  confirmOk,
  confirmCancel,
  // 面试总结
  interviewAgreed,
  interviewFile,
  isInterviewProcessing,
  isInterviewBusy,
  interviewPendingFileName,
  toggleInterviewAgree,
  interviewStartRequested,
  interviewReselectRequested,
  retryInterviewNow,
  refreshInterviewReport
} = useChat()

const inputAreaRef = ref<InstanceType<typeof InputArea> | null>(null)

// ===== 拖拽上传：热区为右侧整个区域（不含左侧会话列表）=====
const isDragOver = ref(false)
let dragDepth = 0

const onDragEnter = (e: DragEvent) => {
  if (!e.dataTransfer?.types.includes('Files')) return
  dragDepth++
  isDragOver.value = true
}

const onDragLeave = (e: DragEvent) => {
  // relatedTarget 为 null 表示拖出了窗口，直接复位
  if (e.relatedTarget === null) {
    dragDepth = 0
    isDragOver.value = false
    return
  }
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) isDragOver.value = false
}

const onDragOver = (e: DragEvent) => {
  if (!e.dataTransfer?.types.includes('Files')) return
  // 必须 prevent，否则浏览器会直接打开文件而非触发 drop
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

const onDrop = (e: DragEvent) => {
  if (!e.dataTransfer?.types.includes('Files')) return
  e.preventDefault()
  dragDepth = 0
  isDragOver.value = false
  if (e.dataTransfer.files.length > 0) {
    handleFiles(e.dataTransfer.files)
  }
}

// 快捷提问后聚焦输入框
const handleQuickPrompt = (prompt: string) => {
  quickPrompt(prompt)
  nextTick(() => inputAreaRef.value?.focusInput())
}

const handleLogout = async () => {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <div class="container">
    <!-- 左侧会话列表 -->
    <SideBar
      :chat-list="chatList"
      :current-chat-id="currentChatId"
      :user="auth.state.user"
      @create-new-chat="createNewChat"
      @select-chat="selectChat"
      @delete-chat="deleteChat"
      @logout="handleLogout"
    />

    <!-- 右侧聊天区域：整个区域可作为拖拽上传热区 -->
    <div
      class="main-content"
      :class="{ 'drag-over': isDragOver }"
      @dragenter="onDragEnter"
      @dragleave="onDragLeave"
      @dragover="onDragOver"
      @drop="onDrop"
    >
      <!-- 全域拖拽提示 -->
      <div v-if="isDragOver" class="drop-overlay">
        <div class="drop-hint">
          <i class="fa-solid fa-cloud-arrow-up"></i>
          <span>松开以上传文件</span>
        </div>
      </div>

      <!-- 消息列表 -->
      <div class="messages-container" ref="messagesContainer">
        <EmptyState v-if="currentChat && currentChat.messages.length === 0" @quick-prompt="handleQuickPrompt" />

        <MessageItem
          v-for="msg in currentChat?.messages || []"
          :key="msg.id"
          :msg="msg"
          :is-sending="isSending"
          :is-last="isLastMessage(msg)"
          :interview-processing="isInterviewProcessing(msg)"
          :interview-loading="isInterviewBusy(msg)"
          :interview-agreed="interviewAgreed"
          :interview-pending-file-name="interviewPendingFileName(msg)"
          @copy="copyMessage"
          @toggle-thinking="toggleThinking"
          @toggle-reference="toggleReference"
          @send-recommend="sendRecommendQuestion"
          @interview-toggle-agree="toggleInterviewAgree"
          @interview-pick-file="interviewReselectRequested"
          @interview-start="interviewStartRequested"
          @interview-retry="retryInterviewNow"
          @interview-refresh-report="refreshInterviewReport"
        />
      </div>

      <!-- 输入区域 -->
      <InputArea
        ref="inputAreaRef"
        :agents="agents"
        :selected-agent="selectedAgent"
        :selected-file="selectedFile"
        :is-uploading="isUploading"
        :input-message="inputMessage"
        :can-send="canSend"
        :is-sending="isSending"
        :interview-file="interviewFile"
        @update:input-message="inputMessage = $event"
        @select-agent="selectAgent"
        @handle-file-select="handleFileSelect"
        @remove-file="removeFile"
        @send="sendMessage"
        @stop="stopMessage"
      />
    </div>
  </div>

  <!-- 连接状态提示 -->
  <ConnectionError :message="connectionError || ''" @retry="testConnection" />

  <!-- 自定义确认对话框 -->
  <ConfirmDialog
    :show="showConfirmDialog"
    :title="confirmTitle"
    :message="confirmMessage"
    @ok="confirmOk"
    @cancel="confirmCancel"
  />
</template>
