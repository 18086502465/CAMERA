<template>
  <Teleport to="body">
    <div v-if="visible" ref="rootRef" class="img-viewer" tabindex="-1" @keydown="onKeydown">
      <div class="img-viewer__mask" @click="close" />

      <div class="img-viewer__toolbar" @click.stop>
        <div class="img-viewer__title">
          <strong>{{ title }}</strong>
          <span v-if="subtitle">{{ subtitle }}</span>
        </div>
        <div class="img-viewer__actions">
          <el-button-group size="small">
            <el-button :disabled="scale <= MIN_SCALE" @click="zoomOut">－</el-button>
            <el-button class="scale-btn" @click="resetZoom">{{ Math.round(scale * 100) }}%</el-button>
            <el-button :disabled="scale >= MAX_SCALE" @click="zoomIn">＋</el-button>
          </el-button-group>
          <el-button size="small" @click="rotateBy(-90)">左旋</el-button>
          <el-button size="small" @click="rotateBy(90)">右旋</el-button>
          <el-button size="small" @click="toggleOriginal">{{ isOriginal ? '适应窗口' : '原始大小' }}</el-button>
          <el-button size="small" type="primary" @click="close">关闭</el-button>
        </div>
      </div>

      <button v-if="total > 1" class="img-viewer__nav prev" type="button" @click.stop="go(-1)">‹</button>

      <div
        ref="stageRef"
        class="img-viewer__stage"
        :class="{ dragging: dragging, zoomed: scale > 1.001 }"
        @wheel.prevent="onWheel"
        @mousedown="onMouseDown"
        @dblclick="onDoubleClick"
      >
        <img
          v-if="currentSrc"
          :src="currentSrc"
          class="img-viewer__img"
          :style="imageStyle"
          draggable="false"
          @load="onImageLoad"
        />
        <div v-else class="img-viewer__empty">当前图片不可用</div>
      </div>

      <button v-if="total > 1" class="img-viewer__nav next" type="button" @click.stop="go(1)">›</button>

      <div class="img-viewer__counter">{{ index + 1 }} / {{ total }}</div>

      <div v-if="total > 1" class="img-viewer__thumbs" @click.stop>
        <div
          v-for="(item, i) in images"
          :key="item.key || item.url || i"
          class="img-viewer__thumb"
          :class="{ active: i === index }"
          :title="item.name || `第 ${i + 1} 张`"
          @click="goTo(i)"
        >
          <img v-if="item.url" :src="item.url" alt="" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  images: { type: Array, default: () => [] },
  index: { type: Number, default: 0 },
  title: { type: String, default: '图片查看' },
  subtitle: { type: String, default: '' }
})
const emit = defineEmits(['update:visible', 'update:index', 'close'])

const MIN_SCALE = 0.1
const MAX_SCALE = 10

const rootRef = ref(null)
const stageRef = ref(null)
const scale = ref(1)
const offsetX = ref(0)
const offsetY = ref(0)
const rotation = ref(0)
const dragging = ref(false)
const naturalWidth = ref(0)
const naturalHeight = ref(0)

const total = computed(() => props.images.length)
const current = computed(() => props.images[props.index] || null)
const currentSrc = computed(() => current.value?.url || '')
/** 适应窗口时图片相对原始像素的缩放比，用于计算「原始大小」所需的 scale */
const fitScale = computed(() => {
  if (!naturalWidth.value || !naturalHeight.value || !stageRef.value) return 1
  const box = stageRef.value.getBoundingClientRect()
  if (!box.width || !box.height) return 1
  return Math.min(box.width / naturalWidth.value, box.height / naturalHeight.value, 1)
})
const isOriginal = computed(() => Math.abs(scale.value - 1 / (fitScale.value || 1)) < 0.02)

const imageStyle = computed(() => ({
  transform: `translate(${offsetX.value}px, ${offsetY.value}px) rotate(${rotation.value}deg) scale(${scale.value})`
}))

const clampScale = value => Math.min(MAX_SCALE, Math.max(MIN_SCALE, Number(value.toFixed(4))))

const resetView = () => {
  scale.value = 1
  offsetX.value = 0
  offsetY.value = 0
  rotation.value = 0
}

const zoomIn = () => { scale.value = clampScale(scale.value * 1.25) }
const zoomOut = () => { scale.value = clampScale(scale.value / 1.25) }
const resetZoom = () => { scale.value = 1; offsetX.value = 0; offsetY.value = 0 }

const toggleOriginal = () => {
  if (isOriginal.value) {
    resetZoom()
    return
  }
  const target = 1 / (fitScale.value || 1)
  scale.value = clampScale(target)
  offsetX.value = 0
  offsetY.value = 0
}

const rotateBy = deg => {
  rotation.value = (rotation.value + deg) % 360
  if (rotation.value < 0) rotation.value += 360
}

const onWheel = event => {
  const factor = event.deltaY < 0 ? 1.15 : 1 / 1.15
  scale.value = clampScale(scale.value * factor)
}

const onDoubleClick = () => {
  if (scale.value > 1.001) {
    resetZoom()
    return
  }
  scale.value = clampScale(1 / (fitScale.value || 1) * 1.4)
}

let dragStart = null
const onMouseDown = event => {
  if (event.button !== 0 || !currentSrc.value) return
  dragging.value = true
  dragStart = { x: event.clientX, y: event.clientY, ox: offsetX.value, oy: offsetY.value }
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
}
const onMouseMove = event => {
  if (!dragStart) return
  offsetX.value = dragStart.ox + (event.clientX - dragStart.x)
  offsetY.value = dragStart.oy + (event.clientY - dragStart.y)
}
const onMouseUp = () => {
  dragging.value = false
  dragStart = null
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
}

const onImageLoad = event => {
  const target = event.target
  if (!target) return
  naturalWidth.value = target.naturalWidth || 0
  naturalHeight.value = target.naturalHeight || 0
}

const go = delta => {
  if (total.value <= 1) return
  const next = (props.index + delta + total.value) % total.value
  goTo(next)
}
const goTo = target => {
  if (target < 0 || target >= total.value) return
  emit('update:index', target)
  resetView()
}

const close = () => {
  emit('update:visible', false)
  emit('close')
}

const onKeydown = event => {
  const key = event.key
  if (key === 'Escape') { event.preventDefault(); close(); return }
  if (key === 'ArrowLeft') { event.preventDefault(); go(-1); return }
  if (key === 'ArrowRight') { event.preventDefault(); go(1); return }
  if (key === '+' || key === '=') { event.preventDefault(); zoomIn(); return }
  if (key === '-' || key === '_') { event.preventDefault(); zoomOut(); return }
  if (key === '0') { event.preventDefault(); resetZoom(); return }
}

watch(() => props.visible, async visible => {
  if (visible) {
    resetView()
    await nextTick()
    rootRef.value?.focus()
  } else {
    onMouseUp()
  }
})

watch(() => props.index, () => {
  resetView()
})

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
})

defineExpose({ resetView })
</script>

<style scoped>
.img-viewer{position:fixed;inset:0;z-index:4000;display:flex;align-items:center;justify-content:center;outline:none}
.img-viewer__mask{position:absolute;inset:0;background:rgba(8,12,22,.88)}
.img-viewer__toolbar{position:absolute;top:0;left:0;right:0;z-index:3;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 16px;background:linear-gradient(180deg,rgba(8,12,22,.92),rgba(8,12,22,0));color:#e2e8f0}
.img-viewer__title{display:flex;align-items:baseline;gap:10px;min-width:0}
.img-viewer__title strong{font-size:15px;color:#f8fafc;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.img-viewer__title span{font-size:12px;color:#94a3b8;white-space:nowrap}
.img-viewer__actions{display:flex;align-items:center;gap:8px;flex:none}
.img-viewer__actions :deep(.el-button){margin-left:0}
.img-viewer__actions .scale-btn{min-width:68px}
.img-viewer__stage{position:relative;z-index:2;width:100%;height:100%;display:flex;align-items:center;justify-content:center;overflow:hidden;cursor:grab;padding:60px 72px 84px;box-sizing:border-box}
.img-viewer__stage.zoomed{cursor:move}
.img-viewer__stage.dragging{cursor:grabbing}
.img-viewer__img{max-width:100%;max-height:100%;object-fit:contain;user-select:none;-webkit-user-drag:none;transform-origin:center center;transition:transform .12s ease-out}
.img-viewer__stage.dragging .img-viewer__img{transition:none}
.img-viewer__empty{color:#cbd5e1;font-size:14px}
.img-viewer__nav{position:absolute;top:50%;z-index:3;transform:translateY(-50%);width:44px;height:44px;border:none;border-radius:50%;background:rgba(255,255,255,.9);color:#0f172a;font-size:26px;font-weight:800;cursor:pointer;box-shadow:0 6px 18px rgba(15,23,42,.28)}
.img-viewer__nav.prev{left:16px}
.img-viewer__nav.next{right:16px}
.img-viewer__nav:hover{background:#fff}
.img-viewer__counter{position:absolute;left:50%;bottom:96px;transform:translateX(-50%);z-index:3;padding:4px 12px;border-radius:999px;background:rgba(15,23,42,.78);color:#fff;font-size:12px;font-weight:700}
.img-viewer__thumbs{position:absolute;left:0;right:0;bottom:14px;z-index:3;display:flex;justify-content:center;gap:8px;padding:0 16px;overflow-x:auto}
.img-viewer__thumb{width:64px;height:48px;border-radius:6px;overflow:hidden;border:2px solid rgba(148,163,184,.45);background:#1e293b;cursor:pointer;flex:none}
.img-viewer__thumb.active{border-color:#60a5fa;box-shadow:0 0 0 2px rgba(96,165,250,.3)}
.img-viewer__thumb img{width:100%;height:100%;object-fit:cover;display:block}
@media (max-width:900px){.img-viewer__stage{padding:56px 44px 80px}.img-viewer__toolbar{flex-direction:column;align-items:flex-start}.img-viewer__nav{width:38px;height:38px;font-size:22px}}
</style>
