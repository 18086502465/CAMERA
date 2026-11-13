<template>
  <div class="base-state" :class="`is-${type}`">
    <span class="state-icon">{{ icon || defaultIcon }}</span>
    <strong class="state-title">{{ title || defaultTitle }}</strong>
    <span class="state-text">{{ text || defaultText }}</span>
    <slot />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  type: { type: String, default: 'empty' },
  icon: String,
  title: String,
  text: String
})

const defaultIcon = computed(() => ({
  empty: '📭',
  error: '⚠️',
  loading: '⏳'
}[props.type] || '📭'))

const defaultTitle = computed(() => ({
  empty: '暂无数据',
  error: '加载失败',
  loading: '加载中'
}[props.type] || '暂无数据'))

const defaultText = computed(() => ({
  empty: '当前筛选条件下没有可展示的数据',
  error: '请刷新重试，或联系管理员排查接口和网络状态',
  loading: '正在获取最新数据，请稍候'
}[props.type] || '当前筛选条件下没有可展示的数据'))
</script>

<style scoped>
.base-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 180px;
  padding: 44px 20px;
  color: var(--color-text-placeholder);
  text-align: center;
  background: linear-gradient(180deg, #fff 0%, var(--color-bg-page) 100%);
  border: 1px dashed #dbe3ef;
  border-radius: 12px;
}
.state-icon { font-size: 36px; line-height: 1; }
.state-title { color: var(--color-text-regular); font-size: 15px; }
.state-text {
  max-width: 460px;
  color: var(--color-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.base-state.is-error {
  background: #fff7ed;
  border-color: #fed7aa;
}
.base-state.is-error .state-title { color: #c2410c; }
.base-state.is-loading {
  background: var(--color-primary-bg);
  border-color: #bfdbfe;
}
</style>
