<template>
  <div class="page-container">
    <div class="page-header">
      <h2>👤 个人中心</h2>
    </div>

    <el-row :gutter="24">
      <!-- 用户信息卡片 -->
      <el-col :xs="24" :md="8">
        <el-card shadow="never" class="profile-card">
          <div class="avatar-section">
            <el-avatar :size="96" class="profile-avatar">
              {{ userStore.userName?.charAt(0) || 'U' }}
            </el-avatar>
            <h3>{{ userStore.userName }}</h3>
            <el-tag :type="userStore.isAdmin ? 'danger' : 'info'" size="small">
              {{ userStore.isAdmin ? '管理员' : '普通用户' }}
            </el-tag>
            <p class="user-factory" v-if="profile?.factoryName">
              🏭 {{ profile.factoryName }}
            </p>
          </div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="用户名">{{ profile?.username || '-' }}</el-descriptions-item>
            <el-descriptions-item label="真实姓名">{{ profile?.realName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ profile?.role === 'ADMIN' ? '管理员' : '普通用户' }}</el-descriptions-item>
            <el-descriptions-item label="所属工厂">{{ profile?.factoryName || '未绑定' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <!-- 修改密码：后端无自助改密端点（原 /api/auth/password 实测 405）。
           ★ 2026-11-25 深度评审：原页面摆出一个必然失败的表单，用户填完三项才收到
           「暂不支持」——给了可用外观却不可用，与项目「诚实降级」真源冲突。
           改为 upfront 说明 + 管理员可用的服务器改密命令，不再诱导填写。 -->
      <el-col :xs="24" :md="16">
        <el-card shadow="never">
          <template #header>
            <span style="font-weight:600;font-size:15px">🔒 修改密码</span>
          </template>
          <el-alert
            type="info"
            :closable="false"
            show-icon
            title="系统暂不支持页面自助改密"
            description="请由管理员在服务器上执行重置脚本（口令不会显示在页面或日志中）：sudo /main/app/dist/scripts/reset-password.sh --password '新口令'"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useUserStore } from '../../stores/user'
import { getProfile } from '../../api/auth'

const userStore = useUserStore()
const profile = ref(null)

const loadProfile = async () => {
  try {
    const res = await getProfile()
    profile.value = res.data.data
    // 同步到 store
    userStore.user = res.data.data
  } catch (e) {
    console.error('加载个人信息失败', e)
  }
}

onMounted(() => {
  loadProfile()
})
</script>

<style scoped>
.page-container { padding: 0; }
.page-header {
  margin-bottom: 16px;
}
.page-header h2 { margin: 0; font-size: 18px; color: #1a1a2e; }
.profile-card { margin-bottom: 16px; }
.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 16px 0 20px;
}
.avatar-section h3 { margin: 0; font-size: 18px; color: #1a1a2e; }
.profile-avatar { background: #409eff; color: #fff; font-size: 36px; }
.user-factory { font-size: 13px; color: #909399; margin: 4px 0 0; }
</style>
