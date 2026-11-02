<template>
  <div class="status-cards">
    <div class="card-item card-running">
      <div class="card-inner">
        <span class="dot green"></span>
        <div>
          <div class="card-label">运行中</div>
          <div class="card-value">{{ stats.running || 0 }}</div>
        </div>
      </div>
    </div>
    <div class="card-item card-stopped">
      <div class="card-inner">
        <span class="dot gray"></span>
        <div>
          <div class="card-label">已停止</div>
          <div class="card-value">{{ stats.stopped || 0 }}</div>
        </div>
      </div>
    </div>
    <div class="card-item card-fault">
      <div class="card-inner">
        <span class="dot red"></span>
        <div>
          <div class="card-label">故障</div>
          <div class="card-value">{{ stats.fault || 0 }}</div>
        </div>
      </div>
    </div>
    <div class="card-item card-standby">
      <div class="card-inner">
        <span class="dot yellow"></span>
        <div>
          <div class="card-label">待机</div>
          <div class="card-value">{{ stats.standby || 0 }}</div>
        </div>
      </div>
    </div>
    <div class="card-item card-offline">
      <div class="card-inner">
        <span class="dot black"></span>
        <div>
          <div class="card-label">离线</div>
          <div class="card-value">{{ stats.offline || 0 }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({ stats: { type: Object, default: () => ({}) } })
</script>

<style scoped>
.status-cards {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 14px;
  margin-bottom: 16px;
}

.card-item {
  border-radius: 10px;
  padding: 16px 20px;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  transition: transform 0.2s;
}

.card-item:hover { transform: translateY(-2px); }

.card-inner {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dot {
  width: 12px; height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
}

.dot.green  { background: #67c23a; box-shadow: 0 0 6px #67c23a88; }
.dot.gray   { background: #909399; box-shadow: 0 0 6px #90939988; }
.dot.red    { background: #f56c6c; box-shadow: 0 0 6px #f56c6c88; }
.dot.yellow { background: #e6a23c; box-shadow: 0 0 6px #e6a23c88; }
.dot.black  { background: #303133; box-shadow: 0 0 6px #30313388; }

.card-label { font-size: 13px; color: #909399; margin-bottom: 2px; }
.card-value { font-size: 28px; font-weight: 700; color: #1a1a2e; }

@media (max-width: 768px) {
  .status-cards { grid-template-columns: repeat(2, 1fr); }
}
</style>
