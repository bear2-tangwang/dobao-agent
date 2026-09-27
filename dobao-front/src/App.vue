<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { useChat } from '@/composables/useChat'
import SideBar from '@/components/SideBar.vue'
import EmptyState from '@/components/EmptyState.vue'
import MessageItem from '@/components/MessageItem.vue'
import InputArea from '@/components/InputArea.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ConnectionError from '@/components/ConnectionError.vue'

const {
  backendUrl,
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
  confirmCancel
} = useChat()

const inputAreaRef = ref<InstanceType<typeof InputArea> | null>(null)

// 快捷提问后聚焦输入框
const handleQuickPrompt = (prompt: string) => {
  quickPrompt(prompt)
  nextTick(() => inputAreaRef.value?.focusInput())
}
</script>

<template>
  <!-- 科技感光晕效果 -->
  <div class="glow-effect glow-effect-1"></div>
  <div class="glow-effect glow-effect-2"></div>
  <div class="glow-effect glow-effect-3"></div>

  <div class="container">
    <!-- 左侧会话列表 -->
    <SideBar
      :chat-list="chatList"
      :current-chat-id="currentChatId"
      :backend-url="backendUrl"
      @create-new-chat="createNewChat"
      @select-chat="selectChat"
      @delete-chat="deleteChat"
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
          v-for="(msg, index) in currentChat?.messages || []"
          :key="index"
          :msg="msg"
          :is-sending="isSending"
          :is-last="isLastMessage(msg)"
          @copy="copyMessage"
          @toggle-thinking="toggleThinking"
          @toggle-reference="toggleReference"
          @send-recommend="sendRecommendQuestion"
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