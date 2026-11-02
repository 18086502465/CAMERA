<template>
  <BaseState :type="type" :icon="icon" :title="title" :text="text">
    <slot />
  </BaseState>
</template>

<script setup>
import BaseState from '@/components/common/BaseState.vue'

defineProps({
  type: { type: String, default: 'empty' },
  icon: String,
  title: String,
  text: String
})
</script>
