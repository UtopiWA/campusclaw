<script setup>
import { computed, onMounted, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import BaseModal from '../components/BaseModal.vue'
import { apiRequest } from '../services/api.js'
import { authState } from '../services/auth.js'

const materials = ref([])
const selectedFile = ref(null)
const fileInput = ref(null)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const query = ref('')
const uploadOpen = ref(false)
const dragActive = ref(false)
const viewOpen = ref(false)
const viewLoading = ref(false)
const previewContent = ref('')
const activeMaterial = ref(null)
const downloadBusyId = ref(null)
const renameOpen = ref(false)
const renameTitle = ref('')
const deleteOpen = ref(false)

const isTeacher = computed(() => authState.user?.role === 'teacher')
const fileCount = computed(() => materials.value.filter((material) => material.hasFile).length)
const filteredMaterials = computed(() => {
  const keyword = query.value.trim().toLocaleLowerCase('zh-CN')
  if (!keyword) return materials.value
  return materials.value.filter((material) =>
    [material.title, material.originalFilename].some((value) => value?.toLocaleLowerCase('zh-CN').includes(keyword)),
  )
})

async function loadMaterials() {
  error.value = ''
  try {
    materials.value = await apiRequest('/api/materials')
  } catch (exception) {
    error.value = exception.message
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
  if (busy.value) return
  uploadOpen.value = false
  selectedFile.value = null
  if (fileInput.value) fileInput.value.value = ''
}

async function upload() {
  if (!selectedFile.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  const data = new FormData()
  data.append('file', selectedFile.value)
  try {
    await apiRequest('/api/materials/upload', { method: 'POST', body: data })
    uploadOpen.value = false
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    notice.value = '讲义已上传，并完成知识库入库。'
    await loadMaterials()
  } catch (exception) {
    error.value = exception.message
  } finally {
    busy.value = false
  }
}

async function viewMaterial(material) {
  if (!material.hasFile) return
  activeMaterial.value = material
  previewContent.value = ''
  viewOpen.value = true
  viewLoading.value = true
  error.value = ''
  try {
    previewContent.value = await apiRequest(`/api/materials/${material.id}/content`, { responseType: 'text' })
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
  busy.value = true
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
    busy.value = false
  }
}

function openDelete(material) {
  activeMaterial.value = material
  deleteOpen.value = true
}

async function remove() {
  if (!activeMaterial.value) return
  busy.value = true
  error.value = ''
  try {
    await apiRequest(`/api/materials/${activeMaterial.value.id}`, { method: 'DELETE' })
    deleteOpen.value = false
    notice.value = '材料及关联知识条目已删除。'
    await loadMaterials()
  } catch (exception) {
    error.value = exception.message
  } finally {
    busy.value = false
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

onMounted(loadMaterials)
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
          <label class="search-box">
            <AppIcon name="search" /><span class="sr-only">搜索讲义</span>
            <input v-model="query" type="search" placeholder="搜索标题或文件名" />
          </label>
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
              </div>
              <p>{{ material.originalFilename || '系统示例知识条目（无原始文件）' }}</p>
              <div class="material-meta">
                <span>更新于 {{ formatDate(material.updatedAt || material.createdAt) }}</span>
                <span v-if="material.hasFile">{{ formatSize(material.fileSizeBytes) }}</span><span v-else>文件操作不可用</span>
              </div>
            </div>
            <div class="material-actions">
              <button class="button button--soft" type="button" :disabled="!material.hasFile" :aria-label="`查看 ${material.title}`" :title="material.hasFile ? '在线查看' : '系统示例条目没有原始文件'" @click="viewMaterial(material)"><AppIcon name="eye" /><span>查看</span></button>
              <button class="button button--soft" type="button" :disabled="!material.hasFile || downloadBusyId === material.id" :aria-label="`下载 ${material.title}`" :title="material.hasFile ? '下载原文件' : '系统示例条目没有原始文件'" @click="downloadMaterial(material)"><AppIcon name="download" /><span>{{ downloadBusyId === material.id ? '下载中' : '下载' }}</span></button>
              <template v-if="isTeacher">
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

  <BaseModal v-if="uploadOpen" title="上传讲义并入库" description="系统将解析正文并生成本班知识片段。" @close="closeUpload">
    <label class="file-drop" :class="{ 'file-drop--active': dragActive, 'file-drop--selected': selectedFile }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="dropFile">
      <input ref="fileInput" type="file" accept=".txt,.md,text/plain,text/markdown" @change="chooseFile" />
      <span class="file-drop-icon"><AppIcon :name="selectedFile ? 'file' : 'upload'" :size="26" /></span>
      <strong>{{ selectedFile?.name || '点击选择，或将文件拖到这里' }}</strong>
      <span>{{ selectedFile ? formatSize(selectedFile.size) : '支持 UTF-8 编码的 .txt、.md，最大 2 MiB' }}</span>
    </label>
    <template #footer>
      <button class="button button--secondary" type="button" :disabled="busy" @click="closeUpload">取消</button>
      <button class="button button--primary" type="button" :disabled="!selectedFile || busy" @click="upload">{{ busy ? '正在解析入库…' : '上传并入库' }}</button>
    </template>
  </BaseModal>

  <BaseModal v-if="viewOpen" :title="activeMaterial?.title || '查看讲义'" :description="activeMaterial?.originalFilename || ''" wide @close="viewOpen = false">
    <div v-if="viewLoading" class="preview-loading">正在读取文件…</div><pre v-else class="file-preview">{{ previewContent }}</pre>
    <template #footer>
      <button class="button button--secondary" type="button" @click="viewOpen = false">关闭</button>
      <button class="button button--primary" type="button" @click="downloadMaterial(activeMaterial)"><AppIcon name="download" />下载原文件</button>
    </template>
  </BaseModal>

  <BaseModal v-if="renameOpen" title="重命名材料" description="只修改展示标题，不改变原文件名。" @close="renameOpen = false">
    <form class="modal-form" @submit.prevent="rename"><label>材料标题<input v-model="renameTitle" maxlength="255" required autofocus /></label></form>
    <template #footer>
      <button class="button button--secondary" type="button" :disabled="busy" @click="renameOpen = false">取消</button>
      <button class="button button--primary" type="button" :disabled="busy || !renameTitle.trim()" @click="rename">保存修改</button>
    </template>
  </BaseModal>

  <BaseModal v-if="deleteOpen" title="删除这份材料？" @close="deleteOpen = false">
    <div class="delete-warning"><span><AppIcon name="trash" /></span><p>“{{ activeMaterial?.title }}”及其关联知识条目和原文件将一并删除，此操作不可撤销。</p></div>
    <template #footer>
      <button class="button button--secondary" type="button" :disabled="busy" @click="deleteOpen = false">取消</button>
      <button class="button button--danger" type="button" :disabled="busy" @click="remove">{{ busy ? '删除中…' : '确认删除' }}</button>
    </template>
  </BaseModal>
</template>
