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
const error = ref('')
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
    error.value = '请输入 1～1000 个字符的问题。'
    return null
  }
  return value
}

function conversationHistory() {
  // 只把依据问答的纯文本轮次传给服务端，检索卡片和引用内容不进入历史。
  return messages.value
    .filter((item) => item.kind === 'ask' && (item.role === 'user' || item.role === 'assistant'))
    .map((item) => ({ role: item.role, content: item.content }))
    .slice(-6)
}

async function scrollToLatest() {
  await nextTick()
  thread.value?.lastElementChild?.scrollIntoView?.({ behavior: 'smooth', block: 'end' })
}

// 同一个输入器承载依据问答和直接检索，结果都追加到当前消息流中。
async function submit() {
  const value = validate()
  if (!value || busy.value) return
  const kind = interaction.value
  const history = conversationHistory()
  messages.value.push({ id: ++messageId, role: 'user', kind, content: value })
  query.value = ''
  busy.value = true
  error.value = ''
  await scrollToLatest()
  try {
    if (kind === 'ask') {
      const result = await askKnowledge(value, history)
      messages.value.push({
        id: ++messageId,
        role: 'assistant',
        kind: 'ask',
        content: result.answer,
        citations: result.citations || [],
      })
    } else {
      const result = await searchKnowledge(value, mode.value)
      messages.value.push({
        id: ++messageId,
        role: 'assistant',
        kind: 'search',
        content: result.message,
        mode: result.mode,
        hits: result.hits || [],
      })
    }
  } catch (exception) {
    error.value = exception instanceof ApiError && exception.status === 503
      ? '检索依赖暂时不可用，请稍后重试。'
      : exception.message
  } finally {
    busy.value = false
    await scrollToLatest()
  }
}

function askSuggestion(value) {
  query.value = value
  interaction.value = 'ask'
  submit()
}

function clearConversation() {
  if (busy.value) return
  messages.value = []
  error.value = ''
  query.value = ''
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
        <button v-if="hasMessages" class="button button--soft" type="button" :disabled="busy" @click="clearConversation">
          新对话
        </button>
      </header>

      <main ref="thread" class="chat-thread" :class="{ 'chat-thread--empty': !hasMessages }" aria-live="polite">
        <section v-if="!hasMessages" class="chat-welcome">
          <span class="chat-welcome-icon"><AppIcon name="bot" :size="30" /></span>
          <h2>今天想从讲义中了解什么？</h2>
          <p>回答只使用当前班级已完成解析的讲义，并在正文下直接列出可核对的引用片段。</p>
          <div class="chat-suggestions">
            <button v-for="suggestion in suggestions" :key="suggestion" type="button" @click="askSuggestion(suggestion)">
              <span>{{ suggestion }}</span><span aria-hidden="true">↗</span>
            </button>
          </div>
        </section>

        <template v-for="message in messages" :key="message.id">
          <article v-if="message.role === 'user'" class="chat-message chat-message--user">
            <div class="chat-user-bubble">{{ message.content }}</div>
          </article>

          <article v-else class="chat-message chat-message--assistant">
            <div class="chat-assistant-avatar" aria-hidden="true"><AppIcon name="bot" /></div>
            <div class="chat-assistant-content">
              <p v-if="message.kind === 'ask'" class="chat-answer">{{ message.content }}</p>

              <ol v-if="message.kind === 'ask' && message.citations.length" class="chat-citations">
                <li v-for="(citation, citationIndex) in message.citations" :key="`${message.id}-${citationIndex}`" class="chat-citation">
                  <div class="chat-citation-heading">
                    <strong>[{{ citationIndex + 1 }}] {{ citation.materialTitle }}</strong>
                    <RouterLink :to="`/materials?material=${citation.materialId}`">查看全文</RouterLink>
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
                      <div class="chat-hit-meta">
                        <span>原文位置 {{ hit.startOffset }}–{{ hit.endOffset }}</span>
                        <span>综合 {{ score(hit.finalScore) }}</span>
                        <span v-if="hit.keywordRank">关键词 #{{ hit.keywordRank }}</span>
                        <span v-if="hit.vectorRank">语义 #{{ hit.vectorRank }}</span>
                      </div>
                    </div>
                    <RouterLink :to="`/materials?material=${hit.materialId}`">查看材料</RouterLink>
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
        <p v-if="error" class="feedback feedback--error chat-error" role="alert">{{ error }}</p>
        <form class="chat-composer" @submit.prevent="submit">
          <div class="chat-composer-tools">
            <div class="chat-kind-picker" aria-label="查询方式">
              <button type="button" :disabled="busy" :class="{ active: interaction === 'ask' }" @click="interaction = 'ask'">依据问答</button>
              <button type="button" :disabled="busy" :class="{ active: interaction === 'search' }" @click="interaction = 'search'">直接检索</button>
            </div>
            <div v-if="interaction === 'search'" class="chat-mode-picker" aria-label="检索模式">
              <button v-for="item in modes" :key="item.value" type="button" :disabled="busy" :class="{ active: mode === item.value }" @click="mode = item.value">{{ item.label }}</button>
            </div>
          </div>
          <div class="chat-input-row">
            <textarea
              v-model="query"
              maxlength="1000"
              rows="2"
              :placeholder="interaction === 'ask' ? '向班级知识库提问…' : '输入要检索的关键词或问题…'"
              aria-label="问题或检索词"
              @keydown.enter.exact.prevent="submit"
            />
            <button class="chat-send" type="submit" :disabled="busy || !query.trim()" aria-label="发送">
              <span aria-hidden="true">↑</span>
            </button>
          </div>
        </form>
        <p class="chat-disclaimer">CampusClaw 可能理解有误，请结合下方讲义原文核对。</p>
      </footer>
    </div>
  </AppShell>
</template>
