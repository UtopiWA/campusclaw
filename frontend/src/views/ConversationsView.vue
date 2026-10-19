<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import BaseModal from '../components/BaseModal.vue'
import { listAssistants } from '../services/agents.js'
import {
  createConversation, deleteConversation, getConversation, listConversations, streamConversationMessage,
} from '../services/conversations.js'
import { renderAssistantMarkdown } from '../utils/assistantMarkdown.js'
import { createStreamTextRenderer } from '../utils/streamTextRenderer.js'
import { toolReferenceLabel } from '../utils/toolDisplay.js'

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
const conversationDeleteTarget = ref(null)
const conversationDeleteError = ref('')
const deletingConversation = ref(false)
const expandedCitations = ref(new Set())

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
  question.value = ''
  expandedCitations.value = new Set()
  if (!currentId.value) {
    current.value = null
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    current.value = await getConversation(currentId.value)
  } catch (exception) {
    error.value = exception.message
    current.value = null
  } finally {
    loading.value = false
  }
  if (current.value) await scrollLatest(false)
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

function streamMessage(event, assistantMessage, renderer, streamState) {
  // delta 只追加服务端声明的新增文本；完成 ID 由已提交的数据库消息提供。
  if (event.event === 'meta') {
    assistantMessage.skillName = event.data.skillName
    assistantMessage.phase = 'GENERATING'
    // meta 中的是候选而不是最终采用引用；正文完成前不展示引用卡片。
    assistantMessage.pendingCitations = event.data.citations || []
    assistantMessage.toolCalls = event.data.toolCalls || []
  } else if (event.event === 'delta') {
    assistantMessage.phase = 'ANSWERING'
    renderer.enqueue(event.data.text)
  } else if (event.event === 'done') {
    streamState.messageId = event.data.messageId
  } else if (event.event === 'error') {
    streamState.error = event.data.message || '回答生成中断，请重试。'
  }
}

function streamStatusText(message) {
  if (message.content || message.status !== 'STREAMING') return ''
  return message.phase === 'GENERATING' ? '正在生成回答…' : '正在检索资料…'
}

function appendStreamCharacter(assistantMessage, character) {
  assistantMessage.content += character
  void scrollLatest(false)
}

function requestConversationDelete(item) {
  if (busy.value && item.id === currentId.value) return
  conversationDeleteError.value = ''
  conversationDeleteTarget.value = item
}

async function confirmConversationDelete() {
  const target = conversationDeleteTarget.value
  if (!target || deletingConversation.value) return
  deletingConversation.value = true
  conversationDeleteError.value = ''
  try {
    await deleteConversation(target.id)
    conversationDeleteTarget.value = null
    await loadSidebar()
    if (target.id === currentId.value) {
      question.value = ''
      current.value = null
      await router.push('/conversations')
    }
  } catch (exception) {
    conversationDeleteError.value = exception.message
  } finally {
    deletingConversation.value = false
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
  // 后续 delta 必须修改同一个响应式代理；修改入数组前的原始对象不会触发 Vue 重绘。
  const assistantMessage = reactive({
    id: `local-assistant-${Date.now()}`, role: 'assistant', content: '', citations: [],
    pendingCitations: [], skillName: null, status: 'STREAMING', phase: 'RETRIEVING',
    toolCalls: [],
    retryQuestion: value, error: '',
  })
  const streamState = { messageId: null, error: '' }
  const renderer = createStreamTextRenderer((character) => appendStreamCharacter(assistantMessage, character))
  current.value.messages.push(userMessage, assistantMessage)
  await scrollLatest()
  try {
    const result = await streamConversationMessage(current.value.id, value,
      (event) => streamMessage(event, assistantMessage, renderer, streamState))
    await renderer.drain()
    if (streamState.error) {
      assistantMessage.status = 'FAILED'
      assistantMessage.error = streamState.error
    } else if (!result.doneSeen || !streamState.messageId) {
      assistantMessage.status = 'FAILED'
      assistantMessage.error = '连接在回答完成前中断。'
    } else {
      assistantMessage.id = streamState.messageId
      assistantMessage.status = 'COMPLETED'
    }
    if (assistantMessage.status === 'COMPLETED') {
      // 完成事件只在数据库提交后发送，随后从服务端恢复权威消息与标题。
      current.value = await getConversation(current.value.id)
      await loadSidebar()
    }
  } catch (exception) {
    await renderer.drain()
    assistantMessage.status = 'FAILED'
    assistantMessage.error = exception.message
  } finally {
    busy.value = false
    await scrollLatest()
  }
}

function referencedCitations(message) {
  const referencedNumbers = new Set()
  for (const match of (message.content || '').matchAll(/\[(\d+)]/g)) {
    referencedNumbers.add(Number(match[1]))
  }
  return (message.citations || []).filter((citation) => referencedNumbers.has(Number(citation.number)))
}

function citationKey(message, citation) {
  return `${message.id}-${citation.number}`
}

function citationIsExpanded(message, citation) {
  return expandedCitations.value.has(citationKey(message, citation))
}

function toggleCitation(message, citation) {
  const key = citationKey(message, citation)
  const next = new Set(expandedCitations.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedCitations.value = next
}

function toolStatusLabel(status) {
  return { ok: '调用成功', denied: '调用被拒绝', error: '调用失败' }[status] || status
}

function handleAnswerClick(message, event) {
  const citation = event.target.closest?.('[data-citation-number]')
  if (citation) void focusCitation(message, Number(citation.dataset.citationNumber))
}

function executionSteps(message) {
  // 执行过程只重组消息快照中已有的事实，不推断未返回的内部耗时或隐藏步骤。
  const citations = referencedCitations(message)
  const toolCalls = message.toolCalls || []
  const steps = [{
    key: 'skill',
    label: '技能路由',
    status: message.skillName ? 'ok' : 'neutral',
    detail: message.skillName ? `命中“${message.skillName}”技能。` : '当前消息未记录命中的技能。',
  }, {
    key: 'materials',
    label: '材料依据',
    status: citations.length ? 'ok' : 'neutral',
    detail: citations.length
      ? `回答正文采用 ${citations.length} 条讲义引用。`
      : '回答正文未采用可展示的讲义引用。',
  }]
  if (toolCalls.length) {
    toolCalls.forEach((tool, index) => steps.push({
      key: `tool-${index}-${tool.ref}`,
      label: `MCP 工具 · ${toolReferenceLabel(tool.ref)}`,
      status: tool.status,
      detail: `${toolStatusLabel(tool.status)}：${tool.detail}`,
    }))
  } else {
    steps.push({
      key: 'tools-none', label: 'MCP 工具', status: 'neutral', detail: '本轮没有可展示的 MCP 工具调用。',
    })
  }
  const answerState = {
    COMPLETED: ['ok', '回答已完成并保存。'],
    FAILED: ['error', '回答中断；页面保留已生成内容，并可重新发送问题。'],
    STREAMING: ['active', message.phase === 'GENERATING' ? '正在生成回答。' : '正在接收回答。'],
  }[message.status] || ['neutral', '当前消息没有可识别的生成状态。']
  steps.push({ key: 'answer', label: '回答生成', status: answerState[0], detail: answerState[1] })
  return steps
}

async function focusCitation(message, number) {
  await nextTick()
  document.getElementById(`conversation-citation-${message.id}-${number}`)?.focus()
}

watch(currentId, loadCurrent)
onMounted(initialize)
</script>

<template>
  <AppShell current="assistant-chat">
    <div class="conversation-page" :class="{ 'conversation-page--collapsed': listCollapsed }">
      <aside class="conversation-list" :class="{ 'conversation-list--open': drawerOpen }">
        <div class="conversation-list-head"><strong>助手对话</strong><button class="icon-button conversation-drawer-close" type="button" aria-label="关闭会话列表" @click="drawerOpen = false"><AppIcon name="close" /></button></div>
        <button class="button button--primary conversation-new" type="button" :disabled="busy || !availableAssistants.length" @click="router.push('/conversations')">＋ 新对话</button>
        <nav aria-label="历史会话">
          <div v-for="item in conversations" :key="item.id" class="conversation-list-item" :class="{ active: item.id === currentId }">
            <button class="conversation-list-select" type="button" @click="selectConversation(item.id)"><strong>{{ item.title }}</strong><span>{{ item.assistantName }}</span></button>
            <button class="conversation-list-delete" type="button" :disabled="busy && item.id === currentId" :aria-label="`删除会话 ${item.title}`" @click="requestConversationDelete(item)"><AppIcon name="trash" :size="14" /></button>
          </div>
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
            <p v-if="availableAssistants.length">选定智能体后，每轮回答都会先检索教师当前绑定的讲义，并应用匹配技能。</p>
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
                  <div v-if="message.toolCalls?.length" class="conversation-tools" aria-label="本轮工具调用">
                    <span v-for="tool in message.toolCalls" :key="tool.ref" :class="`tool-call tool-call--${tool.status}`"><strong>{{ toolReferenceLabel(tool.ref) }}</strong><small>{{ toolStatusLabel(tool.status) }} · {{ tool.detail }}</small></span>
                  </div>
                  <div class="chat-answer" @click="handleAnswerClick(message, $event)"><span v-if="streamStatusText(message)" class="stream-status">{{ streamStatusText(message) }}</span><div v-if="message.content" class="chat-markdown" v-html="renderAssistantMarkdown(message.content, message.citations || [])"></div><span v-if="message.status === 'STREAMING'" class="stream-cursor" aria-label="正在生成"></span></div>
                  <details class="conversation-process">
                    <summary>AI 执行过程 <span>{{ executionSteps(message).length }} 个可观察阶段</span></summary>
                    <ol>
                      <li v-for="step in executionSteps(message)" :key="step.key" :class="`conversation-process-step conversation-process-step--${step.status}`">
                        <span class="conversation-process-marker" aria-hidden="true"></span>
                        <div><strong>{{ step.label }}</strong><p>{{ step.detail }}</p></div>
                      </li>
                    </ol>
                  </details>
                  <div v-if="message.status === 'FAILED'" class="conversation-failed" role="status"><strong>回答已中断</strong><span>{{ message.error || '已保留生成的部分内容。' }}</span><button v-if="message.retryQuestion" type="button" :disabled="busy" @click="send(message.retryQuestion)">重新发送问题</button></div>
                  <ol v-if="referencedCitations(message).length" class="chat-citations">
                    <li v-for="citation in referencedCitations(message)" :id="`conversation-citation-${message.id}-${citation.number}`" :key="citation.number" class="chat-citation" tabindex="-1">
                      <div class="chat-citation-heading"><strong>[{{ citation.number }}] {{ citation.materialTitle }}</strong><RouterLink :to="`/materials?material=${citation.materialId}&chunk=${citation.chunkId}`">查看上下文</RouterLink></div>
                      <p v-show="citationIsExpanded(message, citation)" :id="`conversation-citation-excerpt-${message.id}-${citation.number}`">{{ citation.excerpt }}</p>
                      <button class="chat-citation-toggle" type="button" :aria-expanded="citationIsExpanded(message, citation)" :aria-controls="`conversation-citation-excerpt-${message.id}-${citation.number}`" @click="toggleCitation(message, citation)">{{ citationIsExpanded(message, citation) ? '收起片段' : '展开片段' }}</button>
                    </li>
                  </ol>
                </div>
              </template>
            </article>
          </template>
        </main>

        <footer v-if="current" class="conversation-composer-wrap">
          <form class="chat-composer" @submit.prevent="send()"><div class="chat-input-row"><textarea v-model="question" rows="2" maxlength="500" placeholder="向教学助手提问…" aria-label="问题" :disabled="busy" @keydown.enter.exact.prevent="send()" /><button class="chat-send" type="submit" :disabled="busy || !question.trim()" aria-label="发送"><span aria-hidden="true">↑</span></button></div><div class="conversation-composer-meta"><span>{{ current.assistantName }}</span><span>{{ [...question].length }}/500</span></div></form>
          <p class="chat-disclaimer">回答前会检索智能体绑定的讲义；回答将保存到当前会话，请结合引用核对。</p>
        </footer>
      </section>
    </div>

    <BaseModal v-if="conversationDeleteTarget" title="删除会话" description="该会话的所有消息将一并删除。" @close="!deletingConversation && (conversationDeleteTarget = null)">
      <div class="delete-warning"><span><AppIcon name="trash" /></span><p>确定删除“{{ conversationDeleteTarget.title }}”吗？此操作无法撤销。</p></div>
      <p v-if="conversationDeleteError" class="feedback feedback--error" role="alert">{{ conversationDeleteError }}</p>
      <template #footer><button class="button button--secondary" type="button" :disabled="deletingConversation" @click="conversationDeleteTarget = null">取消</button><button class="button button--danger" type="button" :disabled="deletingConversation" @click="confirmConversationDelete">{{ deletingConversation ? '删除中…' : '确认删除' }}</button></template>
    </BaseModal>
  </AppShell>
</template>
