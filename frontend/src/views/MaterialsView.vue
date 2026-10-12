<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import BaseModal from '../components/BaseModal.vue'
import { apiRequest } from '../services/api.js'
import { authState } from '../services/auth.js'

const materials = ref([])
const selectedFile = ref(null)
const fileInput = ref(null)
const uploadBusy = ref(false)
const renameBusy = ref(false)
const deleteBusy = ref(false)
const rebuildBusy = ref(false)
const refreshBusy = ref(false)
const retryBusyIds = ref(new Set())
const error = ref('')
const notice = ref('')
const query = ref('')
const uploadOpen = ref(false)
const dragActive = ref(false)
const viewOpen = ref(false)
const viewLoading = ref(false)
const previewContent = ref('')
const citationContext = ref(null)
const activeMaterial = ref(null)
const downloadBusyId = ref(null)
const renameOpen = ref(false)
const renameTitle = ref('')
const deleteOpen = ref(false)
const rebuildOpen = ref(false)
const rebuildStrategy = ref('AUTO')
const rebuildMax = ref(800)
const rebuildOverlap = ref(10)
const rebuildBreak = ref('PARAGRAPH')
const rebuildPreprocess = ref(false)
let statusRefreshTimer = null
let pollDelay = 1500
let pollStartedAt = 0
const route = useRoute()

const isTeacher = computed(() => authState.user?.role === 'teacher')
const fileCount = computed(() => materials.value.filter((material) => material.hasFile).length)
// Filtering stays client-side because the lesson-sized material list is already scoped by class on the server.
const filteredMaterials = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return materials.value
  return materials.value.filter((material) =>
    [material.title, material.originalFilename].some((value) => value?.toLocaleLowerCase('zh-CN').includes(keyword)),
  )
})

async function loadMaterials({ manual = false } = {}) {
  if (manual) {
    refreshBusy.value = true
    pollDelay = 1500
    pollStartedAt = Date.now()
  }
  try {
    materials.value = await apiRequest('/api/materials')
    scheduleStatusRefresh()
  } catch (exception) {
    error.value = exception.message
  } finally {
    refreshBusy.value = false
  }
}

function scheduleStatusRefresh() {
  if (statusRefreshTimer) clearTimeout(statusRefreshTimer)
  const processing = materials.value.some((material) => ['PENDING', 'INDEXING'].includes(material.indexStatus))
  if (!processing || document.hidden) {
    statusRefreshTimer = null
    return
  }
  if (!pollStartedAt) pollStartedAt = Date.now()
  if (Date.now() - pollStartedAt >= 60_000) {
    statusRefreshTimer = null
    return
  }
  // 轮询逐步退避，避免孤立 PENDING 对后端产生永久高频请求。
  statusRefreshTimer = setTimeout(() => loadMaterials(), pollDelay)
  pollDelay = Math.min(Math.round(pollDelay * 1.6), 10_000)
}

function handleVisibilityChange() {
  if (document.hidden) {
    if (statusRefreshTimer) clearTimeout(statusRefreshTimer)
    statusRefreshTimer = null
  } else {
    scheduleStatusRefresh()
  }
}

function chooseFile(event) {
  selectedFile.value = event.target.files?.[0] || null
}

function dropFile(event) {
  dragActive.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) selectedFile.value = file
}

function closeUpload() {
  if (uploadBusy.value) return
  uploadOpen.value = false
  selectedFile.value = null
  if (fileInput.value) fileInput.value.value = ''
}

async function upload() {
  if (!selectedFile.value) return
  uploadBusy.value = true
  error.value = ''
  notice.value = ''
  const data = new FormData()
  data.append('file', selectedFile.value)
  try {
    // 上传响应只确认原文件已保存，正文解析和索引会在后台继续执行。
    await apiRequest('/api/materials/upload', { method: 'POST', body: data })
    uploadOpen.value = false
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    notice.value = '讲义已上传，系统正在后台解析并建立索引。'
    pollDelay = 1500
    pollStartedAt = Date.now()
    await loadMaterials()
  } catch (exception) {
    error.value = exception.message
  } finally {
    uploadBusy.value = false
  }
}

async function viewMaterial(material, chunkId = null) {
  if (!material.hasFile && !chunkId) return
  activeMaterial.value = material
  previewContent.value = ''
  citationContext.value = null
  viewOpen.value = true
  viewLoading.value = true
  error.value = ''
  try {
    const requests = []
    if (chunkId) {
      requests.push(apiRequest(`/api/materials/${material.id}/chunks/${chunkId}/context`)
        .then((value) => { citationContext.value = value }))
    }
    if (material.hasFile) {
      // 上传内容始终按纯文本展示，不执行 Markdown 或 HTML。
      requests.push(apiRequest(`/api/materials/${material.id}/content`, { responseType: 'text' })
        .then((value) => { previewContent.value = value }))
    }
    await Promise.all(requests)
  } catch (exception) {
    viewOpen.value = false
    error.value = exception.message
  } finally {
    viewLoading.value = false
  }
}

async function downloadMaterial(material) {
  if (!material?.hasFile || downloadBusyId.value) return
  downloadBusyId.value = material.id
  error.value = ''
  try {
    // Fetch through the authenticated API, then expose the Blob only long enough to trigger a browser download.
    const blob = await apiRequest(`/api/materials/${material.id}/download`, { responseType: 'blob' })
    const href = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = href
    anchor.download = material.originalFilename || `${material.title}.txt`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(href)
  } catch (exception) {
    error.value = exception.message
  } finally {
    downloadBusyId.value = null
  }
}

function openRename(material) {
  activeMaterial.value = material
  renameTitle.value = material.title
  renameOpen.value = true
}

async function rename() {
  const title = renameTitle.value.trim()
  if (!title || title === activeMaterial.value?.title) return
  renameBusy.value = true
  error.value = ''
  try {
    await apiRequest(`/api/materials/${activeMaterial.value.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ title }),
    })
    renameOpen.value = false
    notice.value = '材料标题已更新。'
    await loadMaterials()
  } catch (exception) {
    error.value = exception.message
  } finally {
    renameBusy.value = false
  }
}

function openDelete(material) {
  activeMaterial.value = material
  deleteOpen.value = true
}

function openRebuild(material) {
  activeMaterial.value = material
  rebuildStrategy.value = material.indexStrategy || 'AUTO'
  rebuildOpen.value = true
}

async function rebuildIndex() {
  if (!activeMaterial.value) return
  rebuildBusy.value = true
  error.value = ''
  try {
    const body = { strategy: rebuildStrategy.value }
    if (rebuildStrategy.value === 'CUSTOM') {
      Object.assign(body, {
        maxCodePoints: Number(rebuildMax.value),
        overlapPercent: Number(rebuildOverlap.value),
        breakPreference: rebuildBreak.value,
        preprocess: rebuildPreprocess.value,
      })
    }
    await apiRequest(`/api/materials/${activeMaterial.value.id}/index/rebuild`, {
      method: 'POST', body: JSON.stringify(body),
    })
    rebuildOpen.value = false
    notice.value = '材料索引已重建。'
    await loadMaterials()
  } catch (exception) {
    error.value = exception.message
  } finally {
    rebuildBusy.value = false
  }
}

function indexLabel(material) {
  if (material.indexStatus === 'PENDING') return '等待处理'
  if (material.indexStatus === 'INDEXING') return material.processingStage === 'PARSING' ? '正在解析' : '正在索引'
  return ({ READY: '可检索', FAILED: '处理失败' })[material.indexStatus] || '等待处理'
}

function failureLabel(material) {
  return ({
    INVALID_TEXT: '文件不是有效的 UTF-8 文本，请删除后重新上传。',
    DEPENDENCY_UNAVAILABLE: '模型或向量服务暂时不可用，可以重新处理。',
    PROCESSING_ERROR: '后台处理失败，可以稍后重新处理。',
  })[material.failureCode] || '后台处理失败。'
}

async function retryProcessing(material) {
  if (retryBusyIds.value.has(material.id)) return
  retryBusyIds.value = new Set(retryBusyIds.value).add(material.id)
  error.value = ''
  try {
    await apiRequest(`/api/materials/${material.id}/processing/retry`, { method: 'POST' })
    notice.value = '材料已重新排队处理。'
    pollDelay = 1500
    pollStartedAt = Date.now()
    await loadMaterials()
  } catch (exception) {
    error.value = exception.message
  } finally {
    const pending = new Set(retryBusyIds.value)
    pending.delete(material.id)
    retryBusyIds.value = pending
  }
}

async function remove() {
  if (!activeMaterial.value) return
  deleteBusy.value = true
  error.value = ''
  try {
    await apiRequest(`/api/materials/${activeMaterial.value.id}`, { method: 'DELETE' })
    deleteOpen.value = false
    notice.value = '材料及关联知识条目已删除。'
    await loadMaterials()
  } catch (exception) {
    error.value = exception.message
  } finally {
    deleteBusy.value = false
  }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

function formatSize(bytes) {
  if (bytes == null) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function fileKind(material) {
  if (!material.hasFile) return '知识条目'
  return material.originalFilename?.toLowerCase().endsWith('.md') ? 'Markdown' : '文本文件'
}

async function openRouteContext() {
  const materialId = Number(route.query.material)
  const chunkId = Number(route.query.chunk)
  if (!Number.isInteger(materialId) || !Number.isInteger(chunkId)) return
  const material = materials.value.find((item) => item.id === materialId)
  if (!material) {
    error.value = '引用材料不存在或当前账号无权访问。'
    return
  }
  await viewMaterial(material, chunkId)
}

onMounted(async () => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  await loadMaterials()
  await openRouteContext()
})
onBeforeUnmount(() => {
  if (statusRefreshTimer) clearTimeout(statusRefreshTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<template>
  <AppShell current="materials">
    <div class="page-container">
      <header class="page-heading">
        <div>
          <p class="breadcrumb">知识库 <span>/</span> 讲义管理</p>
          <h1>{{ isTeacher ? '讲义管理' : '讲义与检索' }}</h1>
          <p>{{ isTeacher ? '管理本班讲义，上传后自动解析并沉淀为知识片段。' : '查看和下载教师为本班准备的教学材料。' }}</p>
        </div>
        <button v-if="isTeacher" class="button button--primary button--large" type="button" @click="uploadOpen = true">
          <AppIcon name="upload" />上传讲义
        </button>
      </header>

      <section class="summary-grid" aria-label="材料概览">
        <article class="summary-card">
          <span class="summary-icon summary-icon--red"><AppIcon name="book" /></span>
          <div><strong>{{ materials.length }}</strong><span>本班材料</span></div>
        </article>
        <article class="summary-card">
          <span class="summary-icon summary-icon--gold"><AppIcon name="file" /></span>
          <div><strong>{{ fileCount }}</strong><span>可查看文件</span></div>
        </article>
        <article class="summary-card">
          <span class="summary-icon summary-icon--blue"><AppIcon name="shield" /></span>
          <div><strong>{{ isTeacher ? '管理' : '只读' }}</strong><span>{{ authState.user?.className }}权限</span></div>
        </article>
      </section>

      <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      <p v-if="notice" class="feedback feedback--success" role="status">{{ notice }}</p>

      <section class="content-card materials-card">
        <div class="content-card-header">
          <div><h2>本班讲义</h2><p>共 {{ materials.length }} 份，仅展示当前班级内容</p></div>
          <div class="material-list-tools">
            <label class="search-box">
              <AppIcon name="search" /><span class="sr-only">搜索讲义</span>
              <input v-model="query" type="search" placeholder="搜索标题或文件名" />
            </label>
            <button class="icon-button" type="button" :disabled="refreshBusy" aria-label="刷新材料状态" title="刷新材料状态" @click="loadMaterials({ manual: true })"><AppIcon name="refresh" /></button>
          </div>
        </div>

        <div v-if="filteredMaterials.length" class="material-list">
          <article v-for="material in filteredMaterials" :key="material.id" class="material-row">
            <div class="document-icon" :class="{ 'document-icon--virtual': !material.hasFile }">
              <AppIcon :name="material.hasFile ? 'file' : 'layers'" />
              <span>{{ material.originalFilename?.toLowerCase().endsWith('.md') ? 'MD' : material.hasFile ? 'TXT' : 'KB' }}</span>
            </div>
            <div class="material-main">
              <div class="material-title-line">
                <h3>{{ material.title }}</h3><span class="tag" :class="{ 'tag--muted': !material.hasFile }">{{ fileKind(material) }}</span>
                <span class="index-badge" :class="`index-badge--${(material.indexStatus || 'PENDING').toLowerCase()}`">{{ indexLabel(material) }}</span>
              </div>
              <p>{{ material.originalFilename || '系统示例知识条目（无原始文件）' }}</p>
              <p v-if="material.indexStatus === 'FAILED'" class="material-failure">{{ failureLabel(material) }}</p>
              <div class="material-meta">
                <span>更新于 {{ formatDate(material.updatedAt || material.createdAt) }}</span>
                <span v-if="material.hasFile">{{ formatSize(material.fileSizeBytes) }}</span><span v-else>文件操作不可用</span>
              </div>
            </div>
            <div class="material-actions">
              <button class="button button--soft" type="button" :disabled="!material.hasFile" :aria-label="`查看 ${material.title}`" :title="material.hasFile ? '在线查看' : '系统示例条目没有原始文件'" @click="viewMaterial(material)"><AppIcon name="eye" /><span>查看</span></button>
              <button class="button button--soft" type="button" :disabled="!material.hasFile || downloadBusyId === material.id" :aria-label="`下载 ${material.title}`" :title="material.hasFile ? '下载原文件' : '系统示例条目没有原始文件'" @click="downloadMaterial(material)"><AppIcon name="download" /><span>{{ downloadBusyId === material.id ? '下载中' : '下载' }}</span></button>
              <template v-if="isTeacher">
                <button v-if="material.indexStatus === 'FAILED' && material.retryable" class="button button--soft" type="button" :disabled="retryBusyIds.has(material.id)" :aria-label="`重新处理 ${material.title}`" @click="retryProcessing(material)"><AppIcon name="refresh" /><span>{{ retryBusyIds.has(material.id) ? '排队中' : '重新处理' }}</span></button>
                <button v-if="material.indexStatus === 'READY'" class="icon-button" type="button" :aria-label="`重建索引 ${material.title}`" title="重建索引" @click="openRebuild(material)"><AppIcon name="refresh" /></button>
                <button class="icon-button" type="button" :aria-label="`重命名 ${material.title}`" title="重命名" @click="openRename(material)"><AppIcon name="edit" /></button>
                <button class="icon-button icon-button--danger" type="button" :aria-label="`删除 ${material.title}`" title="删除" @click="openDelete(material)"><AppIcon name="trash" /></button>
              </template>
            </div>
          </article>
        </div>
        <div v-else class="empty-state">
          <span><AppIcon :name="query ? 'search' : 'book'" :size="28" /></span>
          <h3>{{ query ? '没有匹配的讲义' : '本班还没有讲义' }}</h3>
          <p>{{ query ? '尝试更换关键词。' : isTeacher ? '上传第一份讲义，开始建设班级知识库。' : '教师上传后会显示在这里。' }}</p>
        </div>
      </section>
    </div>
  </AppShell>

  <BaseModal v-if="uploadOpen" title="上传讲义并入库" description="文件保存后将在后台解析正文并生成本班知识片段。" @close="closeUpload">
    <label class="file-drop" :class="{ 'file-drop--active': dragActive, 'file-drop--selected': selectedFile }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="dropFile">
      <input ref="fileInput" type="file" accept=".txt,.md,text/plain,text/markdown" @change="chooseFile" />
      <span class="file-drop-icon"><AppIcon :name="selectedFile ? 'file' : 'upload'" :size="26" /></span>
      <strong>{{ selectedFile?.name || '点击选择，或将文件拖到这里' }}</strong>
      <span>{{ selectedFile ? formatSize(selectedFile.size) : '支持 UTF-8 编码的 .txt、.md，最大 2 MiB' }}</span>
    </label>
    <template #footer>
      <button class="button button--secondary" type="button" :disabled="uploadBusy" @click="closeUpload">取消</button>
      <button class="button button--primary" type="button" :disabled="!selectedFile || uploadBusy" @click="upload">{{ uploadBusy ? '正在上传…' : '上传并后台解析' }}</button>
    </template>
  </BaseModal>

  <BaseModal v-if="viewOpen" :title="activeMaterial?.title || '查看讲义'" :description="activeMaterial?.originalFilename || ''" wide @close="viewOpen = false">
    <div v-if="viewLoading" class="preview-loading">正在读取材料与引用上下文…</div>
    <template v-else>
      <section v-if="citationContext" class="citation-context" aria-label="引用上下文">
        <div class="citation-context-heading"><strong>引用片段</strong><span>处理文本位置 {{ citationContext.startOffset }}–{{ citationContext.endOffset }}</span></div>
        <article v-for="neighbor in citationContext.neighbors" :key="neighbor.chunkId" :class="{ 'citation-context-target': neighbor.target }">
          <span>{{ neighbor.target ? '当前引用' : '相邻片段' }}</span><p>{{ neighbor.excerpt }}</p>
        </article>
      </section>
      <pre v-if="activeMaterial?.hasFile" class="file-preview">{{ previewContent }}</pre>
      <p v-else class="preview-no-file">该知识条目没有原始文件，可在上方核对引用上下文。</p>
    </template>
    <template #footer>
      <button class="button button--secondary" type="button" @click="viewOpen = false">关闭</button>
      <button v-if="activeMaterial?.hasFile" class="button button--primary" type="button" @click="downloadMaterial(activeMaterial)"><AppIcon name="download" />下载原文件</button>
    </template>
  </BaseModal>

  <BaseModal v-if="renameOpen" title="重命名材料" description="只修改展示标题，不改变原文件名。" @close="renameOpen = false">
    <form class="modal-form" @submit.prevent="rename"><label>材料标题<input v-model="renameTitle" maxlength="255" required autofocus /></label></form>
    <template #footer>
      <button class="button button--secondary" type="button" :disabled="renameBusy" @click="renameOpen = false">取消</button>
      <button class="button button--primary" type="button" :disabled="renameBusy || !renameTitle.trim()" @click="rename">{{ renameBusy ? '保存中…' : '保存修改' }}</button>
    </template>
  </BaseModal>

  <BaseModal v-if="deleteOpen" title="删除这份材料？" @close="deleteOpen = false">
    <div class="delete-warning"><span><AppIcon name="trash" /></span><p>“{{ activeMaterial?.title }}”及其关联知识条目和原文件将一并删除，此操作不可撤销。</p></div>
    <template #footer>
      <button class="button button--secondary" type="button" :disabled="deleteBusy" @click="deleteOpen = false">取消</button>
      <button class="button button--danger" type="button" :disabled="deleteBusy" @click="remove">{{ deleteBusy ? '删除中…' : '确认删除' }}</button>
    </template>
  </BaseModal>

  <BaseModal v-if="rebuildOpen" title="重建材料索引" :description="activeMaterial?.title || ''" @close="rebuildOpen = false">
    <form class="modal-form index-form" @submit.prevent="rebuildIndex">
      <label>切分策略
        <select v-model="rebuildStrategy">
          <option value="AUTO">自动窗口（800 / 重叠 80）</option>
          <option value="HIERARCHY">Markdown 标题层级</option>
          <option value="CUSTOM">自定义</option>
        </select>
      </label>
      <template v-if="rebuildStrategy === 'CUSTOM'">
        <label>最大字符数（100～2000）<input v-model.number="rebuildMax" type="number" min="100" max="2000" required /></label>
        <label>重叠比例（0～50%）<input v-model.number="rebuildOverlap" type="number" min="0" max="50" required /></label>
        <label>优先断点
          <select v-model="rebuildBreak"><option value="PARAGRAPH">空行</option><option value="LINE">换行</option><option value="SENTENCE">句末</option></select>
        </label>
        <label class="checkbox-label"><input v-model="rebuildPreprocess" type="checkbox" />切分前移除 URL、邮箱并合并连续空白</label>
      </template>
    </form>
    <template #footer>
      <button class="button button--secondary" type="button" :disabled="rebuildBusy" @click="rebuildOpen = false">取消</button>
      <button class="button button--primary" type="button" :disabled="rebuildBusy" @click="rebuildIndex">{{ rebuildBusy ? '重建中…' : '确认重建' }}</button>
    </template>
  </BaseModal>
</template>
