<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import { listAssistants } from '../services/agents.js'
import {
  createConversation, getConversation, listConversations, streamConversationMessage,
} from '../services/conversations.js'

const route = useRoute()
const router = useRouter()
const assistants = ref([])
const conversations = ref([])
const current = ref(null)
const selectedAssistantId = ref('')
const question = ref('')
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const thread = ref(null)
const drawerOpen = ref(false)
const listCollapsed = ref(false)

const currentId = computed(() => route.params.id ? Number(route.params.id) : null)
const availableAssistants = computed(() => assistants.value.filter((item) => item.enabled !== false))

async function loadSidebar() {
  const [assistantItems, conversationItems] = await Promise.all([listAssistants(), listConversations()])
  assistants.value = assistantItems
  conversations.value = conversationItems
  if (!selectedAssistantId.value && availableAssistants.value.length) {
    selectedAssistantId.value = String(availableAssistants.value[0].id)
  }
}

async function loadCurrent() {
  // 会话详情是恢复的事实源，本地流式占位只服务于当前交互反馈。
  if (!currentId.value) {
    current.value = null
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    current.value = await getConversation(currentId.value)
    await scrollLatest(false)
  } catch (exception) {
    error.value = exception.message
    current.value = null
  } finally {
    loading.value = false
  }
}

async function initialize() {
  loading.value = true
  try {
    await loadSidebar()
    await loadCurrent()
  } catch (exception) {
    error.value = exception.message
    loading.value = false
  }
}

async function startConversation() {
  if (!selectedAssistantId.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const created = await createConversation(Number(selectedAssistantId.value))
    await loadSidebar()
    await router.push(`/conversations/${created.id}`)
  } catch (exception) {
    error.value = exception.message
  } finally {
    busy.value = false
  }
}

async function selectConversation(id) {
  drawerOpen.value = false
  if (id !== currentId.value) await router.push(`/conversations/${id}`)
}

function toggleConversationList() {
  if (window.matchMedia?.('(max-width: 760px)').matches) drawerOpen.value = true
  else listCollapsed.value = !listCollapsed.value
}

async function scrollLatest(smooth = true) {
  await nextTick()
  thread.value?.scrollTo?.({ top: thread.value.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
}

function streamMessage(event, assistantMessage) {
  // delta 只追加服务端声明的新增文本；完成 ID 由已提交的数据库消息提供。
  if (event.event === 'meta') {
    assistantMessage.skillName = event.data.skillName
    assistantMessage.citations = event.data.citations || []
  } else if (event.event === 'delta') {
    assistantMessage.content += event.data.text || ''
    scrollLatest()
  } else if (event.event === 'done') {
    assistantMessage.id = event.data.messageId
    assistantMessage.status = 'COMPLETED'
  } else if (event.event === 'error') {
    assistantMessage.status = 'FAILED'
    assistantMessage.error = event.data.message || '回答生成中断，请重试。'
  }
}

async function send(content = question.value) {
  // 发送期间锁定当前会话输入，避免同一浏览器并发两轮导致顺序歧义。
  const value = content.trim()
  const length = [...value].length
  if (busy.value || !current.value) return
  if (length < 2 || length > 500) {
    error.value = '问题长度应为 2～500 个字符。'
    return
  }
  busy.value = true
  error.value = ''
  question.value = ''
  const userMessage = { id: `local-user-${Date.now()}`, role: 'user', content: value, status: 'COMPLETED' }
  const assistantMessage = {
    id: `local-assistant-${Date.now()}`, role: 'assistant', content: '', citations: [],
    skillName: null, status: 'STREAMING', retryQuestion: value, error: '',
  }
  current.value.messages.push(userMessage, assistantMessage)
  await scrollLatest()
  try {
    const result = await streamConversationMessage(current.value.id, value,
      (event) => streamMessage(event, assistantMessage))
    if (!result.doneSeen && assistantMessage.status === 'STREAMING') {
      assistantMessage.status = 'FAILED'
      assistantMessage.error = '连接在回答完成前中断。'
    }
    if (assistantMessage.status === 'COMPLETED') {
      // 完成事件只在数据库提交后发送，随后从服务端恢复权威消息与标题。
      current.value = await getConversation(current.value.id)
      await loadSidebar()
    }
  } catch (exception) {
    assistantMessage.status = 'FAILED'
    assistantMessage.error = exception.message
  } finally {
    busy.value = false
    await scrollLatest()
  }
}

function answerSegments(message) {
  const numbers = new Set((message.citations || []).map((item) => Number(item.number)))
  const segments = []
  const pattern = /\[(\d+)]/g
  let cursor = 0
  let match
  while ((match = pattern.exec(message.content || '')) !== null) {
    if (match.index > cursor) segments.push({ type: 'text', text: message.content.slice(cursor, match.index) })
    const number = Number(match[1])
    segments.push(numbers.has(number) ? { type: 'citation', text: match[0], number } : { type: 'text', text: match[0] })
    cursor = pattern.lastIndex
  }
  if (cursor < (message.content || '').length) segments.push({ type: 'text', text: message.content.slice(cursor) })
  return segments
}

async function focusCitation(message, number) {
  await nextTick()
  document.getElementById(`conversation-citation-${message.id}-${number}`)?.focus()
}

watch(currentId, loadCurrent)
onMounted(initialize)
</script>

<template>
  <AppShell current="assistant">
    <div class="conversation-page" :class="{ 'conversation-page--collapsed': listCollapsed }">
      <aside class="conversation-list" :class="{ 'conversation-list--open': drawerOpen }">
        <div class="conversation-list-head"><strong>助手对话</strong><button class="icon-button conversation-drawer-close" type="button" aria-label="关闭会话列表" @click="drawerOpen = false"><AppIcon name="close" /></button></div>
        <button class="button button--primary conversation-new" type="button" :disabled="busy || !availableAssistants.length" @click="router.push('/conversations')">＋ 新对话</button>
        <nav aria-label="历史会话">
          <button v-for="item in conversations" :key="item.id" type="button" :class="{ active: item.id === currentId }" @click="selectConversation(item.id)">
            <strong>{{ item.title }}</strong><span>{{ item.assistantName }}</span>
          </button>
          <p v-if="!conversations.length">暂无历史会话</p>
        </nav>
      </aside>
      <button v-if="drawerOpen" class="conversation-drawer-backdrop" type="button" aria-label="关闭会话列表" @click="drawerOpen = false"></button>

      <section class="conversation-main">
        <header class="conversation-header">
          <button class="icon-button conversation-menu" type="button" aria-label="切换会话列表" @click="toggleConversationList"><span aria-hidden="true">☰</span></button>
          <div><strong>{{ current?.assistantName || '新对话' }}</strong><span>{{ current ? current.title : '选择一个智能体开始' }}</span></div>
        </header>
        <p v-if="error" class="feedback feedback--error conversation-error" role="alert">{{ error }}</p>

        <main ref="thread" class="conversation-thread" aria-live="polite">
          <div v-if="loading" class="conversation-empty"><p>正在加载会话…</p></div>
          <section v-else-if="!current" class="conversation-empty">
            <span><AppIcon name="bot" :size="30" /></span><h1>开始一次教学对话</h1>
            <p v-if="availableAssistants.length">选定智能体后，技能与讲义范围将在会话中锁定；教师后续修改会从下一轮生效。</p>
            <p v-else>当前班级没有可用的智能体，请联系教师先启用一个智能体。</p>
            <form v-if="availableAssistants.length" class="conversation-start" @submit.prevent="startConversation">
              <label><span>选择智能体</span><select v-model="selectedAssistantId"><option v-for="item in availableAssistants" :key="item.id" :value="String(item.id)">{{ item.name }}</option></select></label>
              <button class="button button--primary" type="submit" :disabled="busy">{{ busy ? '创建中…' : '进入对话' }}</button>
            </form>
          </section>

          <template v-else>
            <article v-for="message in current.messages" :key="message.id" class="chat-message" :class="`chat-message--${message.role}`">
              <div v-if="message.role === 'user'" class="chat-user-wrap"><div class="chat-user-bubble">{{ message.content }}</div></div>
              <template v-else>
                <div class="chat-assistant-avatar" aria-hidden="true"><AppIcon name="bot" /></div>
                <div class="chat-assistant-content">
                  <div v-if="message.skillName" class="conversation-skill">已使用技能 · {{ message.skillName }}</div>
                  <p class="chat-answer"><template v-for="(segment, index) in answerSegments(message)" :key="index"><button v-if="segment.type === 'citation'" class="chat-inline-citation" type="button" @click="focusCitation(message, segment.number)">{{ segment.text }}</button><template v-else>{{ segment.text }}</template></template><span v-if="message.status === 'STREAMING'" class="stream-cursor" aria-label="正在生成"></span></p>
                  <div v-if="message.status === 'FAILED'" class="conversation-failed" role="status"><strong>回答已中断</strong><span>{{ message.error || '已保留生成的部分内容。' }}</span><button v-if="message.retryQuestion" type="button" :disabled="busy" @click="send(message.retryQuestion)">重新发送问题</button></div>
                  <ol v-if="message.citations?.length" class="chat-citations">
                    <li v-for="citation in message.citations" :id="`conversation-citation-${message.id}-${citation.number}`" :key="citation.number" class="chat-citation" tabindex="-1">
                      <div class="chat-citation-heading"><strong>[{{ citation.number }}] {{ citation.materialTitle }}</strong><RouterLink :to="`/materials?material=${citation.materialId}&chunk=${citation.chunkId}`">查看上下文</RouterLink></div>
                      <p>{{ citation.excerpt }}</p>
                    </li>
                  </ol>
                </div>
              </template>
            </article>
          </template>
        </main>

        <footer v-if="current" class="conversation-composer-wrap">
          <form class="chat-composer" @submit.prevent="send()"><div class="chat-input-row"><textarea v-model="question" rows="2" maxlength="500" placeholder="向教学助手提问…" aria-label="问题" :disabled="busy" @keydown.enter.exact.prevent="send()" /><button class="chat-send" type="submit" :disabled="busy || !question.trim()" aria-label="发送"><span aria-hidden="true">↑</span></button></div><div class="conversation-composer-meta"><span>{{ current.assistantName }}</span><span>{{ [...question].length }}/500</span></div></form>
          <p class="chat-disclaimer">回答会保存到当前会话；请结合引用讲义核对重要内容。</p>
        </footer>
      </section>
    </div>
  </AppShell>
</template>
