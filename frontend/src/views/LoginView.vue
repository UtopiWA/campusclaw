<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import { login } from '../services/auth.js'

const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)
const route = useRoute()
const router = useRouter()

async function submit() {
  // Successful login returns to the route captured by the guard, or enters the material workspace by default.
  error.value = ''
  busy.value = true
  try {
    await login(username.value.trim(), password.value)
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/materials'
    await router.replace(target)
  } catch (exception) {
    error.value = exception.status === 401 ? '账号或密码错误，请重新输入。' : exception.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="login-layout">
    <section class="login-brand-panel">
      <div class="login-brand">
        <span class="brand-symbol brand-symbol--light" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
        <div><strong>CampusClaw</strong><span>智能教学平台</span></div>
      </div>
      <div class="login-hero">
        <p class="login-kicker">班级知识 · 教研协同</p>
        <h1>让每一份教学经验<br />都成为可复用的知识</h1>
        <p>以班级为安全边界，沉淀教学材料，连接教师与学生的学习过程。</p>
        <ul>
          <li><span><AppIcon name="shield" /></span><div><strong>班级数据隔离</strong><small>服务端保障每个班级的数据边界</small></div></li>
          <li><span><AppIcon name="layers" /></span><div><strong>材料知识沉淀</strong><small>讲义上传后自动解析进入班级知识库</small></div></li>
          <li><span><AppIcon name="bot" /></span><div><strong>智能能力扩展</strong><small>为后续学科助手与教学闭环提供基础</small></div></li>
        </ul>
      </div>
      <p class="login-panel-footer">CampusClaw · 面向中小学的教研智能体平台</p>
    </section>

    <section class="login-form-panel">
      <div class="login-form-wrap">
        <div class="mobile-login-brand"><span class="brand-symbol" aria-hidden="true"><i></i><i></i><i></i><i></i></span><strong>CampusClaw</strong></div>
        <p class="eyebrow">WELCOME BACK</p>
        <h2>登录教学空间</h2>
        <p class="login-intro">使用课程提供的教师或学生账号继续。</p>
        <form class="login-form" @submit.prevent="submit">
          <label><span>账号</span><span class="input-with-icon"><AppIcon name="user" /><input v-model="username" name="username" autocomplete="username" placeholder="请输入账号" required /></span></label>
          <label><span>密码</span><span class="input-with-icon"><AppIcon name="lock" /><input v-model="password" name="password" type="password" autocomplete="current-password" placeholder="请输入密码" required /></span></label>
          <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
          <button class="button button--primary login-submit" type="submit" :disabled="busy">{{ busy ? '正在验证…' : '登录' }}</button>
        </form>
        <div class="login-security"><AppIcon name="lock" /><span>会话与班级权限由服务端安全校验</span></div>
      </div>
    </section>
  </main>
</template>
