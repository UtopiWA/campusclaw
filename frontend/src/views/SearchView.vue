<script setup>
import { computed, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import { ApiError } from '../services/api.js'
import { askKnowledge, searchKnowledge } from '../services/retrieval.js'

const query = ref('')
const mode = ref('hybrid')
const busy = ref(false)
const error = ref('')
const response = ref(null)
const answer = ref(null)

const hasResults = computed(() => Boolean(response.value?.hits?.length))
const modes = [
  { value: 'keyword', label: '关键词', description: '适合精确术语' },
  { value: 'vector', label: '语义', description: '适合同义改写' },
  { value: 'hybrid', label: '混合', description: '综合排序，推荐' },
]

function validate() {
  const value = query.value.trim()
  const length = [...value].length
  if (length < 1 || length > 1000) {
    error.value = '请输入 1～1000 个字符的问题。'
    return null
  }
  return value
}

// 检索与问答共享同一输入，但分别保留“证据列表”和“带引用回答”的视觉状态。
async function submit(kind = 'search') {
  const value = validate()
  if (!value || busy.value) return
  busy.value = true
  error.value = ''
  response.value = null
  answer.value = null
  try {
    if (kind === 'ask') {
      answer.value = await askKnowledge(value)
    } else {
      response.value = await searchKnowledge(value, mode.value)
    }
  } catch (exception) {
    error.value = exception instanceof ApiError && exception.status === 503
      ? '检索依赖暂时不可用，请稍后重试。'
      : exception.message
  } finally {
    busy.value = false
  }
}

function score(value) {
  return value == null ? '—' : Number(value).toFixed(4)
}
</script>

<template>
  <AppShell current="search">
    <div class="page-container retrieval-page">
      <header class="page-heading">
        <div>
          <p class="breadcrumb">知识库 <span>/</span> 知识检索</p>
          <h1>知识检索与依据问答</h1>
          <p>所有结果仅来自当前班级已就绪的讲义切片，并保留材料与位置依据。</p>
        </div>
      </header>

      <section class="content-card retrieval-console">
        <form @submit.prevent="submit('search')">
          <label class="retrieval-query-label" for="retrieval-query">问题或检索词</label>
          <div class="retrieval-query-row">
            <textarea id="retrieval-query" v-model="query" maxlength="1000" rows="3" placeholder="例如：如何设计一节有层次的阅读课？" />
            <button class="button button--primary button--large" type="submit" :disabled="busy">{{ busy ? '处理中…' : '检索依据' }}</button>
          </div>
          <fieldset class="mode-picker">
            <legend>检索模式</legend>
            <label v-for="item in modes" :key="item.value" :class="{ active: mode === item.value }">
              <input v-model="mode" type="radio" name="mode" :value="item.value" />
              <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
            </label>
          </fieldset>
          <div class="ask-action">
            <span>需要整合答案？系统将固定检索前 4 条依据后再调用模型。</span>
            <button class="button button--secondary" type="button" :disabled="busy" @click="submit('ask')"><AppIcon name="bot" />依据问答</button>
          </div>
        </form>
      </section>

      <p v-if="error" class="feedback feedback--error retrieval-feedback" role="alert">{{ error }}</p>

      <section v-if="answer" class="content-card answer-card" aria-live="polite">
        <div class="content-card-header"><div><h2>依据回答</h2><p>回答中的编号与下方引用一一对应</p></div></div>
        <div class="answer-body"><p>{{ answer.answer }}</p></div>
        <ol v-if="answer.citations?.length" class="citation-list">
          <li v-for="citation in answer.citations" :key="citation.number" class="citation-card">
            <div class="citation-heading">
              <div>
                <strong>[{{ citation.number }}] {{ citation.materialTitle }}</strong>
                <span>切片 {{ citation.chunkIndex + 1 }} · Chunk #{{ citation.chunkId }}</span>
              </div>
              <RouterLink :to="`/materials?material=${citation.materialId}`">查看全文</RouterLink>
            </div>
            <p class="citation-excerpt">{{ citation.excerpt }}</p>
          </li>
        </ol>
        <div v-else class="retrieval-empty"><AppIcon name="search" :size="26" /><strong>{{ answer.answer }}</strong><span>未调用自由回答，也没有生成伪引用。</span></div>
      </section>

      <section v-if="response" class="retrieval-results" aria-live="polite">
        <div class="results-heading"><div><h2>检索依据</h2><p>{{ hasResults ? `共 ${response.hits.length} 条可追溯结果` : '没有符合条件的已就绪切片' }}</p></div><span class="tag">{{ response.mode }}</span></div>
        <div v-if="hasResults" class="hit-list">
          <article v-for="hit in response.hits" :key="hit.chunkId" class="content-card hit-card">
            <div class="hit-rank">{{ hit.rank }}</div>
            <div class="hit-main">
              <div class="hit-title"><h3>{{ hit.materialTitle }}</h3><span>切片 {{ hit.chunkIndex + 1 }}</span></div>
              <p class="hit-excerpt">{{ hit.excerpt }}</p>
              <div class="hit-meta"><span>偏移 [{{ hit.startOffset }}, {{ hit.endOffset }})</span><span>Chunk #{{ hit.chunkId }}</span><span>Entry #{{ hit.knowledgeEntryId }}</span></div>
              <div class="hit-scores">
                <span>综合 {{ score(hit.finalScore) }}</span>
                <span v-if="hit.keywordRank">关键词 #{{ hit.keywordRank }} · {{ score(hit.keywordScore) }}</span>
                <span v-if="hit.vectorRank">语义 #{{ hit.vectorRank }} · {{ score(hit.vectorScore) }}</span>
              </div>
            </div>
            <RouterLink class="button button--soft" :to="`/materials?material=${hit.materialId}`">查看材料</RouterLink>
          </article>
        </div>
        <div v-else class="content-card retrieval-empty"><AppIcon name="search" :size="26" /><strong>{{ response.message }}</strong><span>换一个关键词，或确认教师已完成材料索引。</span></div>
      </section>
    </div>
  </AppShell>
</template>
