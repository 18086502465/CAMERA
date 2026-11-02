<template>
  <el-pagination
    v-model:current-page="currentPageProxy"
    v-model:page-size="pageSizeProxy"
    :page-sizes="pageSizes"
    :total="total"
    :layout="layout"
    :size="small ? 'small' : undefined"
    :background="background"
  />
</template>

<script setup>
import { computed } from 'vue'
import { DEFAULT_PAGE_SIZE, DEFAULT_PAGE_SIZE_OPTIONS } from '@/shared/constants/pagination'

const props = defineProps({
  currentPage: { type: Number, default: 1 },
  pageSize: { type: Number, default: DEFAULT_PAGE_SIZE },
  total: { type: Number, default: 0 },
  pageSizes: { type: Array, default: () => DEFAULT_PAGE_SIZE_OPTIONS },
  layout: { type: String, default: 'total, sizes, prev, pager, next' },
  small: { type: Boolean, default: false },
  background: { type: Boolean, default: true }
})

const emit = defineEmits(['update:currentPage', 'update:pageSize', 'change', 'current-change', 'size-change'])

const currentPageProxy = computed({
  get: () => props.currentPage,
  set: value => {
    emit('update:currentPage', value)
    emit('current-change', value)
    emit('change', { currentPage: value, pageSize: props.pageSize })
  }
})

const pageSizeProxy = computed({
  get: () => props.pageSize,
  set: value => {
    emit('update:pageSize', value)
    emit('update:currentPage', 1)
    emit('size-change', value)
    emit('current-change', 1)
    emit('change', { currentPage: 1, pageSize: value })
  }
})
</script>
