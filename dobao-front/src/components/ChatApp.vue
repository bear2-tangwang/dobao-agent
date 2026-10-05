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

    <!-- 右侧聊天区域 -->
    <div class="main-content">
      <!-- 顶部装饰 -->
      <div class="top-decoration">
        <div class="decoration-line"></div>
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
