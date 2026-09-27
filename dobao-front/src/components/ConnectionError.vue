<script setup lang="ts">
defineProps<{
  message: string
}>()

defineEmits<{
  (e: 'retry'): void
}>()
</script>

<template>
  <div v-if="message" class="connection-error">
    <i class="fas fa-exclamation-triangle"></i>
    <span>{{ message }}</span>
    <button @click="$emit('retry')" class="retry-btn">重试</button>
  </div>
</template>