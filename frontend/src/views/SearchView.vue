<script setup>
import { computed, nextTick, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import { ApiError } from '../services/api.js'
import { askKnowledge, searchKnowledge } from '../services/retrieval.js'

const query = ref('')
const mode = ref('hybrid')
const interaction = ref('ask')
const busy = ref(false)
const validationError = ref('')
const messages = ref([])
const thread = ref(null)
let messageId = 0

const modes = [
  { value: 'keyword', label: '关键词' },
  { value: 'vector', label: '语义' },
  { value: 'hybrid', label: '混合' },
]
const suggestions = [
  '帮我概括讲义中的核心教学方法',
  '根据已有材料设计一组课堂提问',
  '这批讲义有哪些可以对照分析的观点？',
]
const hasMessages = computed(() => messages.value.length > 0)

function validate() {
  const value = query.value.trim()
  const length = [...value].length
  if (length < 1 || length > 1000) {
    validationError.value = '请输入 1～1000 个字符的问题。'
    return null
  }
  validationError.value = ''
  return value
}

function conversationHistory() {
  // 失败问题不进入历史，防止下一轮把未获得回答的内容当作已完成对话。
  return messages.value
    .filter((item) => item.kind === 'ask' && item.status !== 'error'
      && (item.role === 'user' || item.role === 'assistant'))
    .map((item) => ({ role: item.role, content: item.content }))
    .slice(-6)
}

async function scrollToLatest() {
  await nextTick()
  if (thread.value) thread.value.scrollTo?.({ top: thread.value.scrollHeight, behavior: 'smooth' })
}

function dependencyMessage(exception) {
  return exception instanceof ApiError && exception.status === 503
    ? '检索依赖暂时不可用，请稍后重试。'
    : exception.message
}

async function runRequest(userMessage) {
  busy.value = true
  userMessage.status = 'pending'
  userMessage.error = ''
  await scrollToLatest()
  try {
    if (userMessage.kind === 'ask') {
      const result = await askKnowledge(userMessage.content, userMessage.history)
      messages.value.push({
        id: ++messageId, role: 'assistant', kind: 'ask', status: 'done',
        content: result.answer, citations: result.citations || [],
      })
    } else {
      const result = await searchKnowledge(userMessage.content, userMessage.mode)
      messages.value.push({
        id: ++messageId, role: 'assistant', kind: 'search', status: 'done',
        content: result.message, mode: result.mode, hits: result.hits || [],
      })
    }
    userMessage.status = 'done'
  } catch (exception) {
    userMessage.status = 'error'
    userMessage.error = dependencyMessage(exception)
  } finally {
    busy.value = false
    await scrollToLatest()
  }
}

async function submit() {
  const value = validate()
  if (!value || busy.value) return
  const kind = interaction.value
  const userMessage = {
    id: ++messageId,
    role: 'user',
    kind,
    mode: kind === 'search' ? mode.value : null,
    content: value,
    history: kind === 'ask' ? conversationHistory() : [],
    status: 'pending',
    error: '',
  }
  messages.value.push(userMessage)
  query.value = ''
  await runRequest(userMessage)
}

async function retryMessage(message) {
  if (busy.value || message.status !== 'error') return
  await runRequest(message)
}

function askSuggestion(value) {
  query.value = value
  interaction.value = 'ask'
  submit()
}

function clearConversation() {
  if (busy.value) return
  messages.value = []
  validationError.value = ''
  query.value = ''
}

/** 只把响应中真实存在的引用编号转换为按钮，其他方括号文本保持纯文本。 */
function answerSegments(message) {
  const citations = new Set((message.citations || []).map((citation) => Number(citation.number)))
  const segments = []
  const pattern = /\[(\d+)]/g
  let cursor = 0
  let match
  while ((match = pattern.exec(message.content)) !== null) {
    if (match.index > cursor) segments.push({ type: 'text', text: message.content.slice(cursor, match.index) })
    const number = Number(match[1])
    segments.push(citations.has(number)
      ? { type: 'citation', text: match[0], number }
      : { type: 'text', text: match[0] })
    cursor = pattern.lastIndex
  }
  if (cursor < message.content.length) segments.push({ type: 'text', text: message.content.slice(cursor) })
  return segments
}

async function focusCitation(messageIdValue, number) {
  await nextTick()
  const target = document.getElementById(`citation-${messageIdValue}-${number}`)
  target?.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
  target?.focus?.({ preventScroll: true })
}

function score(value) {
  return value == null ? '—' : Number(value).toFixed(4)
}
</script>

<template>
  <AppShell current="search">
    <div class="retrieval-chat-page">
      <header class="chat-page-header">
        <div>
          <p class="breadcrumb">知识库 <span>/</span> 智能问答</p>
          <h1>与班级知识库对话</h1>
        </div>
        <button v-if="hasMessages" class="button button--soft" type="button" :disabled="busy" @click="clearConversation">新对话</button>
      </header>

      <main ref="thread" class="chat-thread" :class="{ 'chat-thread--empty': !hasMessages }" aria-live="polite">
        <section v-if="!hasMessages" class="chat-welcome">
          <span class="chat-welcome-icon"><AppIcon name="bot" :size="30" /></span>
          <h2>今天想从讲义中了解什么？</h2>
          <p>回答只使用当前班级已完成解析的讲义，并在正文下直接列出可核对的引用片段。</p>
          <div class="chat-suggestions">
            <button v-for="suggestion in suggestions" :key="suggestion" type="button" :disabled="busy" @click="askSuggestion(suggestion)">
              <span>{{ suggestion }}</span><span aria-hidden="true">→</span>
            </button>
          </div>
        </section>

        <template v-for="message in messages" :key="message.id">
          <article v-if="message.role === 'user'" class="chat-message chat-message--user">
            <div class="chat-user-wrap">
              <div class="chat-user-bubble">{{ message.content }}</div>
              <div v-if="message.status === 'error'" class="chat-message-error" role="alert">
                <span>{{ message.error }}</span>
                <button type="button" :disabled="busy" @click="retryMessage(message)">重试</button>
              </div>
            </div>
          </article>

          <article v-else class="chat-message chat-message--assistant">
            <div class="chat-assistant-avatar" aria-hidden="true"><AppIcon name="bot" /></div>
            <div class="chat-assistant-content">
              <p v-if="message.kind === 'ask'" class="chat-answer">
                <template v-for="(segment, segmentIndex) in answerSegments(message)" :key="segmentIndex"><button v-if="segment.type === 'citation'" class="chat-inline-citation" type="button" :aria-label="`查看引用 ${segment.number}`" @click="focusCitation(message.id, segment.number)">{{ segment.text }}</button><template v-else>{{ segment.text }}</template></template>
              </p>

              <ol v-if="message.kind === 'ask' && message.citations.length" class="chat-citations">
                <li v-for="citation in message.citations" :id="`citation-${message.id}-${citation.number}`" :key="`${message.id}-${citation.number}`" class="chat-citation" tabindex="-1">
                  <div class="chat-citation-heading">
                    <strong>[{{ citation.number }}] {{ citation.materialTitle }}</strong>
                    <RouterLink :to="`/materials?material=${citation.materialId}&chunk=${citation.chunkId}`">查看引用上下文</RouterLink>
                  </div>
                  <p>{{ citation.excerpt }}</p>
                </li>
              </ol>

              <div v-if="message.kind === 'search'" class="chat-search-results">
                <div class="chat-tool-heading">
                  <div><strong>检索结果</strong><span>{{ message.hits.length ? `找到 ${message.hits.length} 条依据` : '没有找到可用依据' }}</span></div>
                  <span class="tag">{{ message.mode }}</span>
                </div>
                <ol v-if="message.hits.length" class="chat-hit-list">
                  <li v-for="(hit, hitIndex) in message.hits" :key="hit.chunkId" class="chat-hit">
                    <span class="chat-hit-number">{{ hitIndex + 1 }}</span>
                    <div class="chat-hit-main">
                      <div class="chat-hit-title"><strong>{{ hit.materialTitle }}</strong><span>引用 {{ hitIndex + 1 }}</span></div>
                      <p>{{ hit.excerpt }}</p>
                      <details class="chat-hit-details">
                        <summary>检索详情</summary>
                        <div class="chat-hit-meta">
                          <span>处理文本位置 {{ hit.startOffset }}–{{ hit.endOffset }}</span>
                          <span>综合 {{ score(hit.finalScore) }}</span>
                          <span v-if="hit.keywordRank">关键词 #{{ hit.keywordRank }}</span>
                          <span v-if="hit.vectorRank">语义 #{{ hit.vectorRank }}</span>
                        </div>
                      </details>
                    </div>
                    <RouterLink :to="`/materials?material=${hit.materialId}&chunk=${hit.chunkId}`">查看上下文</RouterLink>
                  </li>
                </ol>
                <div v-else class="chat-no-result"><AppIcon name="search" /><span>{{ message.content || '资料中未找到相关内容' }}</span></div>
              </div>
            </div>
          </article>
        </template>

        <article v-if="busy" class="chat-message chat-message--assistant chat-message--loading">
          <div class="chat-assistant-avatar" aria-hidden="true"><AppIcon name="bot" /></div>
          <div class="chat-typing"><i></i><i></i><i></i><span>{{ interaction === 'ask' ? '正在整理讲义依据' : '正在检索讲义' }}</span></div>
        </article>
      </main>

      <footer class="chat-composer-wrap">
        <p v-if="validationError" class="feedback feedback--error chat-error" role="alert">{{ validationError }}</p>
        <form class="chat-composer" @submit.prevent="submit">
          <div class="chat-input-row">
            <textarea v-model="query" maxlength="1000" rows="2" :placeholder="interaction === 'ask' ? '向班级知识库提问…' : '输入要检索的关键词或问题…'" aria-label="问题或检索词" @keydown.enter.exact.prevent="submit" />
            <button class="chat-send" type="submit" :disabled="busy || !query.trim()" aria-label="发送"><span aria-hidden="true">↑</span></button>
          </div>
          <div class="chat-composer-tools">
            <label class="chat-tool-select"><span class="sr-only">交互方式</span><select v-model="interaction" :disabled="busy" aria-label="交互方式"><option value="ask">依据问答</option><option value="search">直接检索</option></select></label>
            <label v-if="interaction === 'search'" class="chat-tool-select"><span class="sr-only">检索模式</span><select v-model="mode" :disabled="busy" aria-label="检索模式"><option v-for="item in modes" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
          </div>
        </form>
        <p class="chat-disclaimer">CampusClaw 可能理解有误，请结合下方讲义原文核对。</p>
      </footer>
    </div>
  </AppShell>
</template>
