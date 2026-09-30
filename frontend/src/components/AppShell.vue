<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { authState, logout } from '../services/auth.js'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  current: { type: String, required: true },
})

const router = useRouter()
const isTeacher = computed(() => authState.user?.role === 'teacher')
const roleLabel = computed(() => isTeacher.value ? '教师' : '学生')

// One role-aware definition keeps the shared shell stable; entries without `to` reserve future course modules.
const navItems = computed(() => isTeacher.value
  ? [
      { key: 'home', label: '教师首页', icon: 'home' },
      { key: 'materials', label: '讲义管理', icon: 'book', to: '/materials' },
      { key: 'search', label: '知识检索', icon: 'search', to: '/search' },
      { key: 'assistant', label: '助手配置', icon: 'bot' },
      { key: 'homework', label: '作业与改分', icon: 'homework' },
      { key: 'audit', label: '审计与统计', icon: 'audit' },
    ]
  : [
      { key: 'home', label: '学生首页', icon: 'home' },
      { key: 'materials', label: '讲义资料', icon: 'book', to: '/materials' },
      { key: 'search', label: '知识检索', icon: 'search', to: '/search' },
      { key: 'assistant', label: '助手对话', icon: 'bot' },
      { key: 'homework', label: '交作业', icon: 'homework' },
      { key: 'audit', label: '学情分析', icon: 'audit' },
    ])

async function signOut() {
  await logout()
  await router.replace('/login')
}
</script>

<template>
  <div class="app-frame">
    <aside class="sidebar">
      <div class="sidebar-brand">
        <span class="brand-symbol" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
        <span class="brand-copy"><strong>CampusClaw</strong><small>智能教学平台</small></span>
      </div>

      <nav class="sidebar-nav" aria-label="主导航">
        <p class="nav-section-label">教学空间</p>
        <template v-for="item in navItems" :key="item.key">
          <RouterLink
            v-if="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ active: props.current === item.key }"
            :aria-current="props.current === item.key ? 'page' : undefined"
            :aria-label="item.label"
            :title="item.label"
          >
            <AppIcon :name="item.icon" />
            <span class="nav-item-label">{{ item.label }}</span>
          </RouterLink>
          <span
            v-else
            class="nav-item nav-item--disabled"
            :aria-label="`${item.label}，待后续课程建设`"
            :title="`${item.label} · 待后续课程建设`"
          >
            <AppIcon :name="item.icon" />
            <span class="nav-item-label">{{ item.label }}</span>
            <span class="coming-soon">待建设</span>
          </span>
        </template>
      </nav>

      <div class="sidebar-scope">
        <AppIcon name="shield" />
        <div><strong>班级数据隔离</strong><span>仅访问当前班级数据</span></div>
      </div>
    </aside>

    <div class="app-body">
      <header class="app-topbar">
        <div class="mobile-brand">
          <span class="brand-symbol" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
          <strong>CampusClaw</strong>
        </div>
        <div class="class-context">
          <span class="status-dot"></span>
          <span>{{ authState.user?.className }}</span>
          <span class="context-divider"></span>
          <span>{{ roleLabel }}工作空间</span>
        </div>
        <div class="topbar-account">
          <span class="user-avatar">{{ isTeacher ? '师' : '生' }}</span>
          <span class="account-copy"><strong>{{ authState.user?.username }}</strong><small>{{ roleLabel }}</small></span>
          <button class="topbar-logout" type="button" title="退出登录" @click="signOut">
            <AppIcon name="logout" /><span>退出</span>
          </button>
        </div>
      </header>

      <main class="workspace"><slot /></main>
    </div>
  </div>
</template>
