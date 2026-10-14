<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import BaseModal from '../components/BaseModal.vue'
import {
  createAssistant, createSkill, deleteSkill, listAssistants, listMaterials, listSkills,
  updateAssistant, updateSkill,
} from '../services/agents.js'

const tab = ref('assistants')
const assistants = ref([])
const skills = ref([])
const materials = ref([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const assistantOpen = ref(false)
const skillOpen = ref(false)
const deleteTarget = ref(null)
const saving = ref(false)
const importInput = ref(null)
const assistantDraft = reactive({ id: null, name: '', promptText: '', enabled: true, skillIds: [], materialIds: [] })
const skillDraft = reactive({ id: null, sourceText: '' })
const readyMaterials = computed(() => materials.value.filter((item) => item.indexStatus === 'READY'))

async function load() {
  // 三类数据并行读取，确保绑定选择器与列表来自同一次页面刷新。
  loading.value = true
  error.value = ''
  try {
    ;[assistants.value, skills.value, materials.value] = await Promise.all([
      listAssistants(), listSkills(), listMaterials(),
    ])
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

function editAssistant(item = null) {
  Object.assign(assistantDraft, {
    id: item?.id ?? null,
    name: item?.name ?? '',
    promptText: item?.promptText ?? '',
    enabled: item?.enabled ?? true,
    skillIds: [...(item?.skillIds || [])],
    materialIds: [...(item?.materialIds || [])],
  })
  error.value = ''
  assistantOpen.value = true
}

async function saveAssistant() {
  // 提交完整集合而非差量操作，让后端在单一事务中原子替换绑定。
  saving.value = true
  error.value = ''
  const payload = {
    name: assistantDraft.name,
    promptText: assistantDraft.promptText,
    enabled: assistantDraft.enabled,
    skillIds: [...new Set(assistantDraft.skillIds.map(Number))],
    materialIds: [...new Set(assistantDraft.materialIds.map(Number))],
  }
  try {
    if (assistantDraft.id) await updateAssistant(assistantDraft.id, payload)
    else await createAssistant(payload)
    assistantOpen.value = false
    notice.value = '智能体配置已保存。'
    await load()
  } catch (exception) {
    error.value = exception.message
  } finally {
    saving.value = false
  }
}

function editSkill(item = null) {
  Object.assign(skillDraft, { id: item?.id ?? null, sourceText: item?.sourceText ?? '' })
  error.value = ''
  skillOpen.value = true
}

async function importSkill(event) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    skillDraft.sourceText = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
  } catch {
    error.value = '只能导入合法的 UTF-8 文本文件。'
  } finally {
    event.target.value = ''
  }
}

async function saveSkill() {
  // 服务端解析失败时不关闭弹窗，教师可在原草稿上继续修正。
  saving.value = true
  error.value = ''
  try {
    if (skillDraft.id) await updateSkill(skillDraft.id, skillDraft.sourceText)
    else await createSkill(skillDraft.sourceText)
    skillOpen.value = false
    notice.value = '技能已解析并保存。'
    await load()
  } catch (exception) {
    // 保留原文草稿，便于教师按服务端解析错误继续修正。
    error.value = exception.message
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  saving.value = true
  error.value = ''
  try {
    await deleteSkill(deleteTarget.value.id)
    deleteTarget.value = null
    notice.value = '技能已删除，相关智能体绑定已解除。'
    await load()
  } catch (exception) {
    error.value = exception.message
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <AppShell current="assistant">
    <div class="page-container agent-config-page">
      <header class="page-heading">
        <div><p class="breadcrumb">教学空间 <span>/</span> 助手配置</p><h1>智能体与技能</h1><p>设置角色提示词、确定性技能和可检索讲义范围。</p></div>
        <button class="button button--primary" type="button" @click="tab === 'assistants' ? editAssistant() : editSkill()">
          <span aria-hidden="true">＋</span>{{ tab === 'assistants' ? '新建智能体' : '新建技能' }}
        </button>
      </header>

      <p v-if="error && !assistantOpen && !skillOpen && !deleteTarget" class="feedback feedback--error" role="alert">{{ error }}</p>
      <p v-if="notice" class="feedback feedback--success" role="status">{{ notice }}</p>
      <div class="config-tabs" role="tablist" aria-label="配置类别">
        <button :class="{ active: tab === 'assistants' }" role="tab" type="button" @click="tab = 'assistants'">智能体 <span>{{ assistants.length }}</span></button>
        <button :class="{ active: tab === 'skills' }" role="tab" type="button" @click="tab = 'skills'">技能 <span>{{ skills.length }}</span></button>
      </div>

      <section class="content-card config-card" :aria-busy="loading">
        <div v-if="loading" class="empty-state"><p>正在加载配置…</p></div>
        <div v-else-if="tab === 'assistants'" class="config-grid">
          <article v-for="item in assistants" :key="item.id" class="config-item">
            <div class="config-item-icon"><AppIcon name="bot" /></div>
            <div class="config-item-body">
              <div class="config-item-title"><h2>{{ item.name }}</h2><span class="index-badge" :class="item.enabled ? 'index-badge--ready' : 'tag--muted'">{{ item.enabled ? '已启用' : '已停用' }}</span></div>
              <p>{{ item.promptText || '使用平台默认角色提示词' }}</p>
              <div class="config-tags"><span>{{ item.skillIds?.length || 0 }} 个技能</span><span>{{ item.materialIds?.length || 0 }} 份讲义</span></div>
            </div>
            <button class="button button--soft" type="button" @click="editAssistant(item)"><AppIcon name="edit" :size="15" />编辑</button>
          </article>
          <div v-if="!assistants.length" class="empty-state"><span><AppIcon name="bot" /></span><h3>还没有智能体</h3><p>先创建一个用于学生会话的教学助手。</p></div>
        </div>
        <div v-else class="config-grid">
          <article v-for="item in skills" :key="item.id" class="config-item">
            <div class="config-item-icon config-item-icon--skill"><AppIcon name="layers" /></div>
            <div class="config-item-body">
              <div class="config-item-title"><h2>{{ item.name }}</h2><span class="index-badge" :class="item.enabled ? 'index-badge--ready' : 'tag--muted'">{{ item.enabled ? '已启用' : '已停用' }}</span></div>
              <p>{{ item.instructions }}</p>
              <div class="config-tags"><span>触发词：{{ item.triggerText }}</span><span>范围：{{ item.materialScope }}</span></div>
            </div>
            <div class="config-actions"><button class="icon-button" type="button" aria-label="编辑技能" @click="editSkill(item)"><AppIcon name="edit" /></button><button class="icon-button icon-button--danger" type="button" aria-label="删除技能" @click="deleteTarget = item"><AppIcon name="trash" /></button></div>
          </article>
          <div v-if="!skills.length" class="empty-state"><span><AppIcon name="layers" /></span><h3>还没有技能</h3><p>通过结构化文本添加一个确定性路由技能。</p></div>
        </div>
      </section>
    </div>

    <BaseModal v-if="assistantOpen" :title="assistantDraft.id ? '编辑智能体' : '新建智能体'" description="保存时会完整替换技能和讲义绑定。" wide @close="!saving && (assistantOpen = false)">
      <form id="assistant-form" class="config-form" @submit.prevent="saveAssistant">
        <label>名称<input v-model="assistantDraft.name" required maxlength="64" /></label>
        <label>角色提示词<textarea v-model="assistantDraft.promptText" rows="6" maxlength="2000" placeholder="留空时使用平台默认教学助手角色" /></label>
        <label class="switch-field"><input v-model="assistantDraft.enabled" type="checkbox" /><span>允许新建会话</span></label>
        <fieldset><legend>绑定技能</legend><div class="option-grid"><label v-for="item in skills" :key="item.id"><input v-model="assistantDraft.skillIds" type="checkbox" :value="item.id" /><span><strong>{{ item.name }}</strong><small>{{ item.triggerText }}</small></span></label><p v-if="!skills.length">暂无可绑定技能</p></div></fieldset>
        <fieldset><legend>绑定 READY 讲义</legend><div class="option-grid"><label v-for="item in readyMaterials" :key="item.id"><input v-model="assistantDraft.materialIds" type="checkbox" :value="item.id" /><span><strong>{{ item.title }}</strong><small>{{ item.originalFilename || '虚拟材料' }}</small></span></label><p v-if="!readyMaterials.length">暂无已完成索引的讲义</p></div></fieldset>
        <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      </form>
      <template #footer><button class="button button--secondary" type="button" :disabled="saving" @click="assistantOpen = false">取消</button><button class="button button--primary" form="assistant-form" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存配置' }}</button></template>
    </BaseModal>

    <BaseModal v-if="skillOpen" :title="skillDraft.id ? '编辑技能原文' : '新建技能'" description="服务端会解析字段并返回具体校验错误。" wide @close="!saving && (skillOpen = false)">
      <form id="skill-form" class="config-form" @submit.prevent="saveSkill">
        <div class="skill-editor-toolbar"><span>UTF-8 技能定义</span><button class="button button--soft" type="button" @click="importInput?.click()"><AppIcon name="upload" :size="15" />导入文本</button><input ref="importInput" class="sr-only" type="file" accept="text/plain,.txt,.md" @change="importSkill" /></div>
        <textarea v-model="skillDraft.sourceText" class="skill-editor" rows="17" required spellcheck="false" placeholder="name: 解题引导&#10;triggers: 方程, 求解&#10;material_scope: all&#10;tool: knowledge_search&#10;enabled: true&#10;&#10;引导学生分步思考，不直接给出最终答案。" />
        <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      </form>
      <template #footer><button class="button button--secondary" type="button" :disabled="saving" @click="skillOpen = false">取消</button><button class="button button--primary" form="skill-form" type="submit" :disabled="saving">{{ saving ? '解析中…' : '解析并保存' }}</button></template>
    </BaseModal>

    <BaseModal v-if="deleteTarget" title="删除技能" description="此操作会解除所有智能体对该技能的绑定。" @close="!saving && (deleteTarget = null)">
      <div class="delete-warning"><span><AppIcon name="trash" /></span><p>确定删除“{{ deleteTarget.name }}”吗？讲义和智能体本身不会被删除。</p></div><p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      <template #footer><button class="button button--secondary" type="button" :disabled="saving" @click="deleteTarget = null">取消</button><button class="button button--danger" type="button" :disabled="saving" @click="confirmDelete">确认删除</button></template>
    </BaseModal>
  </AppShell>
</template>
