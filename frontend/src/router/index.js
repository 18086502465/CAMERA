import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/auth'
import { useUserStore } from '../stores/user'
import { handleResourceLoadError } from '../utils/resource-version'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/auth/Login.vue'),
    meta: { public: true }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('../views/auth/Register.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('../components/Layout.vue'),
    redirect: '/camera-status',
    children: [
      {
        path: 'camera-status',
        name: 'CameraStatus',
        component: () => import('../views/camera/CameraStatusView.vue'),
        meta: { title: '相机状态' }
      },
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '实时监控看板' }
      },
      {
        path: 'cameras',
        name: 'CameraList',
        component: () => import('../views/camera/CameraList.vue'),
        meta: { title: '相机监控' }
      },
      {
        path: 'detection',
        name: 'DetectionList',
        component: () => import('../views/detection/DetectionList.vue'),
        meta: { title: '检测记录' }
      },
      {
        path: 'anomaly',
        name: 'AnomalyList',
        component: () => import('../views/anomaly/AnomalyList.vue'),
        meta: { title: '异常告警' }
      },
      // 统计报表
      {
        path: 'stats/overview',
        name: 'StatsOverview',
        redirect: '/dashboard',
        meta: { title: '统计总览' }
      },
      {
        path: 'stats/line-dashboard',
        name: 'LineDashboard',
        redirect: '/dashboard',
        meta: { title: '单产线看板' }
      },
      // 基础数据管理（仅管理员）
      {
        path: 'companies',
        name: 'CompanyList',
        redirect: '/system/config',
        meta: { title: '公司管理', requiresAdmin: true }
      },
      {
        path: 'factories',
        name: 'FactoryList',
        redirect: '/system/config',
        meta: { title: '工厂管理', requiresAdmin: true }
      },
      {
        path: 'lines',
        name: 'LineList',
        redirect: '/system/config',
        meta: { title: '产线管理', requiresAdmin: true }
      },
      {
        path: 'workstations',
        name: 'WorkstationList',
        redirect: '/system/config',
        meta: { title: '工位管理', requiresAdmin: true }
      },
      // 系统管理（仅管理员）
      {
        path: 'users',
        name: 'UserList',
        redirect: '/system/config',
        meta: { title: '用户管理', requiresAdmin: true }
      },
      {
        path: 'brands',
        name: 'BrandList',
        redirect: '/system/config',
        meta: { title: '产品品牌管理', requiresAdmin: true }
      },
      {
        path: 'defect-types',
        name: 'DefectTypeList',
        redirect: '/system/config',
        meta: { title: '缺陷分类管理', requiresAdmin: true }
      },
      {
        path: 'holidays',
        name: 'HolidayConfig',
        redirect: '/system/config',
        meta: { title: '节假日配置', requiresAdmin: true }
      },
      {
        path: 'operation-logs',
        name: 'OperationLog',
        redirect: '/system/config?tab=operationLog',
        meta: { title: '操作日志', requiresAdmin: true }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('../views/profile/Profile.vue'),
        meta: { title: '个人中心' }
      },
      // ===== Phase 2 新增看板页 =====
      {
        path: 'stats/defects',
        name: 'StatsDefects',
        component: () => import('../views/stats/DefectsView.vue'),
        meta: { title: '缺陷统计' }
      },
      {
        path: 'stats/yield',
        name: 'StatsYield',
        component: () => import('../views/stats/YieldView.vue'),
        meta: { title: '良率分析' }
      },
      {
        path: 'stats/images',
        name: 'StatsImages',
        component: () => import('../views/stats/ImagesView.vue'),
        meta: { title: '图像记录' }
      },
      {
        path: 'line-analytics',
        name: 'LineAnalytics',
        component: () => import('../views/stats/LineAnalyticsView.vue'),
        meta: { title: '产线统计' }
      },
      {
        path: 'cameras/:id',
        name: 'CameraDetail',
        component: () => import('../views/camera/CameraDetail.vue'),
        meta: { title: '相机详情' }
      },
      {
        path: 'defects/gallery',
        name: 'DefectGallery',
        component: () => import('../views/defect/GalleryView.vue'),
        meta: { title: '缺陷图库' }
      },
      {
        path: 'system-config',
        name: 'SystemConfigLegacy',
        redirect: '/system/config',
        meta: { title: '系统配置', requiresAdmin: true }
      },
      {
        path: 'system/config',
        name: 'SystemConfig',
        component: () => import('../views/system/ConfigView.vue'),
        meta: { title: '系统配置', requiresAdmin: true }
      },
      {
        // 服务器监控（2026-11-02 新增）
        // 不设 requiresAdmin —— 按决策所有登录用户都能看服务器负载，
        // 现场运维人员排查时也需要这个页面。
        // 后端接口 /api/v1/system/server/metrics 由 SecurityConfig 的
        // /api/v1/system/** → authenticated() 规则保护。
        path: 'server-monitor',
        name: 'ServerMonitor',
        component: () => import('../views/system/ServerMonitorView.vue'),
        meta: { title: '服务器监控' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  const token = getToken()
  const isPublic = to.meta.public

  // 已登录访问登录/注册页 → 跳转首页
  if (token && (to.path === '/login' || to.path === '/register')) {
    next('/camera-status')
    return
  }

  // 公开路由 → 直接放行
  if (isPublic) {
    next()
    return
  }

  // 未登录 → 跳转登录页
  if (!token) {
    next('/login')
    return
  }

  // 需要管理员权限
  if (to.meta.requiresAdmin) {
    const user = JSON.parse(localStorage.getItem('camera_user') || '{}')
    if (!(user.role === 1 || user.role === 'ADMIN')) {
      next('/camera-status')
      return
    }
  }

  next()
})

// ============================================================
// 路由错误兜底：动态 import 失败（2026-11-04）
// ============================================================
// 现场故障：页面是部署前打开的旧版本，懒加载的 chunk 已被新构建替换，
// 请求 404 → "Failed to fetch dynamically imported module" →
// vue-router 的导航 Promise 被 reject → **导航静默中断**。
// 表现为点击【图像记录】【系统配置】URL 完全不变、页面不刷新、也没有后端请求。
//
// 这里统一捕获并交给版本守卫自愈（刷新页面后即可拿到新 chunk）。
router.onError((error, to) => {
  console.warn('[router] 路由加载失败：', to?.fullPath, error)
  handleResourceLoadError(error)
})

export default router
