<template>
  <BaseState type="loading" v-bind="$attrs">
    <slot />
  </BaseState>
</template>

<script setup>
import BaseState from './BaseState.vue'
</script>
