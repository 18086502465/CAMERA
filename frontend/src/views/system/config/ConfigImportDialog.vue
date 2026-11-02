<template>
  <el-dialog
    :model-value="modelValue"
    title="整体导入配置"
    width="720px"
    :close-on-click-modal="false"
    class="config-import-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="import-intro">
      <div>
        <h3>工厂 / 产线 / 工位 / 相机一次性维护</h3>
        <p>适合初始化新工厂、新产线批量上线，或批量调整相机和工位绑定关系。</p>
      </div>
      <el-button size="small" @click="emit('download-template')">下载 Excel 模板</el-button>
    </div>

    <el-form label-width="86px" label-position="left">
      <el-form-item label="导入模式">
        <el-radio-group v-model="modeProxy">
          <el-radio-button label="INCREMENTAL">增量导入</el-radio-button>
          <el-radio-button label="FULL">全量导入</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <el-alert
      v-if="modeProxy === 'INCREMENTAL'"
      type="info"
      show-icon
      :closable="false"
      title="增量导入：按编码新增或更新 Excel 中出现的数据，不删除未出现在文件中的配置。"
      class="import-alert"
    />
    <el-alert
      v-else
      type="warning"
      show-icon
      :closable="false"
      title="全量导入：以 Excel 为准更新配置；文件外相机会被标记为停用/离线，工厂、产线、工位不会物理删除。"
      class="import-alert"
    />

    <el-upload
      class="import-upload"
      drag
      action="#"
      accept=".xlsx,.xls"
      :auto-upload="false"
      :limit="1"
      :file-list="fileList"
      :on-change="file => emit('file-change', file)"
      :on-remove="() => emit('file-remove')"
      :on-exceed="files => emit('file-exceed', files)"
    >
      <div class="upload-icon">📄</div>
      <div class="el-upload__text">拖拽 Excel 到这里，或 <em>点击选择文件</em></div>
      <template #tip>
        <div class="el-upload__tip">仅支持 .xlsx / .xls；首个 Sheet 必须包含模板表头。</div>
      </template>
    </el-upload>

    <div v-if="result" class="import-result">
      <div class="result-title">导入结果</div>
      <div class="result-grid">
        <div><span>数据行</span><strong>{{ result.totalRows || 0 }}</strong></div>
        <div><span>成功</span><strong class="ok">{{ result.successRows || 0 }}</strong></div>
        <div><span>失败</span><strong class="danger">{{ result.failedRows || 0 }}</strong></div>
        <div><span>跳过</span><strong>{{ result.skippedRows || 0 }}</strong></div>
        <div><span>新增配置</span><strong>{{ createdTotal }}</strong></div>
        <div><span>更新配置</span><strong>{{ updatedTotal }}</strong></div>
        <div><span>停用相机</span><strong>{{ result.cameraDisabled || 0 }}</strong></div>
      </div>
      <div v-if="result.warnings?.length" class="result-list warn">
        <b>风险/提醒</b>
        <p v-for="item in result.warnings" :key="item">{{ item }}</p>
      </div>
      <div v-if="result.errors?.length" class="result-list error">
        <b>失败明细</b>
        <p v-for="item in result.errors" :key="item">{{ item }}</p>
      </div>
      <div v-if="result.errors?.length" class="import-fix-tips">
        <b>处理建议</b>
        <ul>
          <li>确认工厂编码、产线编码、工位编码、相机编号在 Excel 中一一对应，不要混用名称作为唯一键。</li>
          <li>检查必填列是否为空，尤其是工厂编码、产线编码、工位编码、相机编号。</li>
          <li>全量导入前建议先导出现有配置备份；修正失败行后可使用增量导入重试。</li>
        </ul>
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :loading="loading" @click="emit('submit')">开始导入</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  importMode: { type: String, default: 'INCREMENTAL' },
  fileList: { type: Array, default: () => [] },
  result: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits([
  'update:modelValue',
  'update:importMode',
  'download-template',
  'file-change',
  'file-remove',
  'file-exceed',
  'submit'
])

const modeProxy = computed({
  get: () => props.importMode,
  set: value => emit('update:importMode', value)
})

const createdTotal = computed(() => {
  const r = props.result || {}
  return (r.factoryCreated || 0) + (r.lineCreated || 0) + (r.workstationCreated || 0) + (r.cameraCreated || 0)
})

const updatedTotal = computed(() => {
  const r = props.result || {}
  return (r.factoryUpdated || 0) + (r.lineUpdated || 0) + (r.workstationUpdated || 0) + (r.cameraUpdated || 0)
})
</script>

<style scoped>
.import-intro { display: flex; justify-content: space-between; gap: 16px; padding: 14px 16px; border: 1px solid #e0f2fe; border-radius: 14px; background: linear-gradient(135deg,#f0f9ff 0%,#ffffff 100%); margin-bottom: 16px; }
.import-intro h3 { margin: 0 0 6px; font-size: 16px; color: #0f172a; }
.import-intro p { margin: 0; color: #64748b; font-size: 13px; line-height: 1.6; }
.import-alert { margin: 8px 0 14px; border-radius: 10px; }
.import-upload { margin-top: 8px; }
.import-upload :deep(.el-upload-dragger) { border-radius: 14px; background: #f8fafc; }
.upload-icon { font-size: 34px; margin: 12px 0 4px; }
.import-result { margin-top: 16px; padding: 14px; border-radius: 14px; border: 1px solid #e5e7eb; background: #fff; }
.result-title { font-size: 14px; font-weight: 800; color: #0f172a; margin-bottom: 10px; }
.result-grid { display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); gap: 10px; margin-bottom: 12px; }
.result-grid div { padding: 10px; border-radius: 12px; background: #f8fafc; border: 1px solid #eef2f7; }
.result-grid span { display: block; font-size: 12px; color: #64748b; margin-bottom: 4px; }
.result-grid strong { color: #0f172a; font-size: 18px; }
.result-grid strong.ok { color: #16a34a; }
.result-grid strong.danger { color: #dc2626; }
.result-list { padding: 10px 12px; border-radius: 12px; margin-top: 10px; font-size: 12px; line-height: 1.6; }
.result-list p { margin: 4px 0 0; }
.result-list.warn { background: #fffbeb; color: #92400e; border: 1px solid #fde68a; }
.result-list.error { background: #fef2f2; color: #b91c1c; border: 1px solid #fecaca; }
.import-fix-tips { margin-top: 10px; padding: 10px 12px; border-radius: 12px; background: #f8fafc; border: 1px solid #dbeafe; color: #475569; font-size: 12px; line-height: 1.7; }
.import-fix-tips b { color: #1d4ed8; }
.import-fix-tips ul { margin: 6px 0 0; padding-left: 18px; }
@media (max-width: 900px) {
  .import-intro { flex-direction: column; }
  .result-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }
}
</style>
