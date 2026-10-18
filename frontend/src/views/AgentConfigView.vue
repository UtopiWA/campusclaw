<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import AppShell from '../components/AppShell.vue'
import BaseModal from '../components/BaseModal.vue'
import {
  createAssistant, createSkill, deleteAssistant, deleteSkill, listAssistants, listMaterials, listSkills,
  updateAssistant, updateSkill,
} from '../services/agents.js'
import {
  connectMcpServer, createMcpServer, deleteMcpServer, disconnectMcpServer,
  listMcpServers, listMcpToolCalls, listMcpTools, updateMcpServer,
} from '../services/mcp.js'
import { buildSkillSource, parseSkillSource } from '../utils/skillSource.js'

const tab = ref('assistants')
const assistants = ref([])
const skills = ref([])
const materials = ref([])
const mcpServers = ref([])
const mcpTools = ref([])
const mcpCalls = ref([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const assistantOpen = ref(false)
const skillOpen = ref(false)
const mcpOpen = ref(false)
const deleteTarget = ref(null)
const saving = ref(false)
const togglingAssistantIds = ref(new Set())
const togglingSkillIds = ref(new Set())
const importInput = ref(null)
const assistantDraft = reactive({ id: null, name: '', promptText: '', enabled: true, skillIds: [], materialIds: [] })
const skillDraft = reactive({
  id: null, name: '', triggerText: '', materialScope: '', instructions: '', enabled: true,
  toolRefs: ['knowledge_search'],
})
const mcpDraft = reactive({ id: null, name: '', baseUrl: 'http://mcp-demo:8090', token: '' })
const readyMaterials = computed(() => materials.value.filter((item) => item.indexStatus === 'READY'))
const toolsForServer = (serverId) => mcpTools.value.filter((item) => item.serverId === serverId)
const schemaSummary = (schema) => {
  const properties = schema?.properties ? Object.keys(schema.properties) : []
  const required = new Set(schema?.required || [])
  if (!properties.length) return '无参数'
  return properties.map((name) => `${name}${required.has(name) ? '*' : ''}`).join('、')
}
const selectableToolRefs = computed(() => {
  const discovered = mcpTools.value.map((item) => ({ ...item, unavailable: false }))
  const known = new Set(discovered.map((item) => item.ref))
  const stale = skillDraft.toolRefs.filter((ref) => ref !== 'knowledge_search' && !known.has(ref))
    .map((ref) => ({ ref, name: ref, description: '当前不可用，请重新连接服务或取消选择', unavailable: true }))
  return [...discovered, ...stale]
})

async function load() {
  // 三类数据并行读取，确保绑定选择器与列表来自同一次页面刷新。
  loading.value = true
  error.value = ''
  try {
    ;[assistants.value, skills.value, materials.value, mcpServers.value, mcpCalls.value] = await Promise.all([
      listAssistants(), listSkills(), listMaterials(), listMcpServers(), listMcpToolCalls(),
    ])
    const toolGroups = await Promise.all(mcpServers.value.filter((item) => item.connected).map(async (server) => {
      const tools = await listMcpTools(server.id)
      return tools.map((tool) => ({ ...tool, serverId: server.id, serverName: server.name, ref: `${tool.name}@${server.name}` }))
    }))
    mcpTools.value = toolGroups.flat()
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
  Object.assign(skillDraft, {
    id: item?.id ?? null,
    name: item?.name ?? '',
    triggerText: item?.triggerText ?? '',
    materialScope: item?.materialScope ?? '',
    instructions: item?.instructions ?? '',
    enabled: item?.enabled ?? true,
    toolRefs: [...(item?.toolRefs || ['knowledge_search'])],
  })
  error.value = ''
  skillOpen.value = true
}

async function importSkill(event) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    const sourceText = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
    Object.assign(skillDraft, parseSkillSource(sourceText), { id: skillDraft.id })
  } catch (exception) {
    error.value = exception instanceof TypeError ? '只能导入合法的 UTF-8 文本文件。' : exception.message
  } finally {
    event.target.value = ''
  }
}

async function saveSkill() {
  // 表单最终仍编译为服务端技能文本，确保唯一解析规则和既有 API 不变。
  saving.value = true
  error.value = ''
  try {
    const sourceText = buildSkillSource(skillDraft)
    if (skillDraft.id) await updateSkill(skillDraft.id, sourceText)
    else await createSkill(sourceText)
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

async function toggleSkill(item, enabled) {
  if (togglingSkillIds.value.has(item.id)) return
  const previousEnabled = item.enabled
  item.enabled = enabled
  togglingSkillIds.value = new Set(togglingSkillIds.value).add(item.id)
  error.value = ''
  try {
    const sourceText = buildSkillSource({
      ...item, enabled, toolRefs: [...(item.toolRefs || ['knowledge_search'])],
    })
    await updateSkill(item.id, sourceText)
    notice.value = `技能“${item.name}”已${enabled ? '启用' : '停用'}。`
  } catch (exception) {
    // 列表先即时反馈操作，失败时显式恢复服务端确认前的状态。
    item.enabled = previousEnabled
    error.value = exception.message
  } finally {
    const pending = new Set(togglingSkillIds.value)
    pending.delete(item.id)
    togglingSkillIds.value = pending
  }
}

function editMcp(item = null) {
  Object.assign(mcpDraft, {
    id: item?.id ?? null,
    name: item?.name ?? '',
    baseUrl: item?.baseUrl ?? 'http://mcp-demo:8090',
    token: '',
  })
  error.value = ''
  mcpOpen.value = true
}

async function saveMcp() {
  saving.value = true
  error.value = ''
  try {
    const payload = { name: mcpDraft.name, baseUrl: mcpDraft.baseUrl, token: mcpDraft.token }
    if (mcpDraft.id) await updateMcpServer(mcpDraft.id, payload)
    else await createMcpServer(payload)
    mcpDraft.token = ''
    mcpOpen.value = false
    notice.value = 'MCP 服务配置已保存，连接后才会发现工具。'
    await load()
  } catch (exception) {
    error.value = exception.message
  } finally {
    saving.value = false
  }
}

async function changeMcpConnection(item) {
  saving.value = true
  error.value = ''
  try {
    if (item.connected) await disconnectMcpServer(item.id)
    else await connectMcpServer(item.id)
    notice.value = item.connected ? 'MCP 服务已断开。' : 'MCP 服务已连接并完成工具发现。'
    await load()
  } catch (exception) {
    error.value = exception.message
  } finally {
    saving.value = false
  }
}

async function toggleAssistant(item) {
  if (togglingAssistantIds.value.has(item.id)) return
  const previousEnabled = item.enabled
  const enabled = !previousEnabled
  item.enabled = enabled
  togglingAssistantIds.value = new Set(togglingAssistantIds.value).add(item.id)
  error.value = ''
  try {
    await updateAssistant(item.id, {
      name: item.name,
      promptText: item.promptText || '',
      enabled,
      skillIds: [...(item.skillIds || [])],
      materialIds: [...(item.materialIds || [])],
    })
    notice.value = `智能体“${item.name}”已${enabled ? '启用' : '停用'}。`
  } catch (exception) {
    // 列表先反馈操作，失败时恢复服务端确认前的状态。
    item.enabled = previousEnabled
    error.value = exception.message
  } finally {
    const pending = new Set(togglingAssistantIds.value)
    pending.delete(item.id)
    togglingAssistantIds.value = pending
  }
}

async function confirmDelete() {
  saving.value = true
  error.value = ''
  try {
    const target = deleteTarget.value
    if (target.type === 'assistant') await deleteAssistant(target.item.id)
    else if (target.type === 'skill') await deleteSkill(target.item.id)
    else await deleteMcpServer(target.item.id)
    deleteTarget.value = null
    notice.value = target.type === 'assistant'
      ? '智能体已删除，相关配置绑定已清理。'
      : target.type === 'skill' ? '技能已删除，相关智能体绑定已解除。' : 'MCP 服务已删除。'
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
  <AppShell current="assistant-config">
    <div class="page-container agent-config-page">
      <header class="page-heading">
        <div><p class="breadcrumb">教学空间 <span>/</span> 助手配置</p><h1>智能体与技能</h1><p>设置角色提示词、确定性技能和可检索讲义范围。</p></div>
        <button class="button button--primary" type="button" @click="tab === 'assistants' ? editAssistant() : tab === 'skills' ? editSkill() : editMcp()">
          <span aria-hidden="true">＋</span>{{ tab === 'assistants' ? '新建智能体' : tab === 'skills' ? '新建技能' : '登记 MCP 服务' }}
        </button>
      </header>

      <p v-if="error && !assistantOpen && !skillOpen && !mcpOpen && !deleteTarget" class="feedback feedback--error" role="alert">{{ error }}</p>
      <p v-if="notice" class="feedback feedback--success" role="status">{{ notice }}</p>
      <div class="config-tabs" role="tablist" aria-label="配置类别">
        <button :class="{ active: tab === 'assistants' }" role="tab" type="button" @click="tab = 'assistants'">智能体 <span>{{ assistants.length }}</span></button>
        <button :class="{ active: tab === 'skills' }" role="tab" type="button" @click="tab = 'skills'">技能 <span>{{ skills.length }}</span></button>
        <button :class="{ active: tab === 'mcp' }" role="tab" type="button" @click="tab = 'mcp'">MCP 工具 <span>{{ mcpServers.length }}</span></button>
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
            <div class="config-actions">
              <button class="button button--soft" type="button" :disabled="togglingAssistantIds.has(item.id)" @click="toggleAssistant(item)">{{ item.enabled ? '停用' : '启用' }}</button>
              <button class="button button--soft" type="button" @click="editAssistant(item)"><AppIcon name="edit" :size="15" />编辑</button>
              <button class="icon-button icon-button--danger" type="button" :aria-label="`删除智能体 ${item.name}`" @click="deleteTarget = { type: 'assistant', item }"><AppIcon name="trash" /></button>
            </div>
          </article>
          <div v-if="!assistants.length" class="empty-state"><span><AppIcon name="bot" /></span><h3>还没有智能体</h3><p>先创建一个用于学生会话的教学助手。</p></div>
        </div>
        <div v-else-if="tab === 'skills'" class="config-grid">
          <article v-for="item in skills" :key="item.id" class="config-item">
            <div class="config-item-icon config-item-icon--skill"><AppIcon name="layers" /></div>
            <div class="config-item-body">
              <div class="config-item-title"><h2>{{ item.name }}</h2><span class="index-badge" :class="item.enabled ? 'index-badge--ready' : 'tag--muted'">{{ item.enabled ? '已启用' : '已停用' }}</span></div>
              <p>{{ item.instructions }}</p>
              <div class="config-tags"><span>触发词：{{ item.triggerText }}</span><span>范围：{{ item.materialScope }}</span></div>
            </div>
            <div class="config-actions">
              <label class="toggle-switch" :title="item.enabled ? '停用技能' : '启用技能'"><input type="checkbox" :checked="item.enabled" :disabled="togglingSkillIds.has(item.id)" :aria-label="`${item.enabled ? '停用' : '启用'}技能 ${item.name}`" @change="toggleSkill(item, $event.target.checked)" /><span></span></label>
              <button class="icon-button" type="button" aria-label="编辑技能" @click="editSkill(item)"><AppIcon name="edit" /></button><button class="icon-button icon-button--danger" type="button" aria-label="删除技能" @click="deleteTarget = { type: 'skill', item }"><AppIcon name="trash" /></button>
            </div>
          </article>
          <div v-if="!skills.length" class="empty-state"><span><AppIcon name="layers" /></span><h3>还没有技能</h3><p>通过结构化文本添加一个确定性路由技能。</p></div>
        </div>
        <div v-else class="mcp-config">
          <div class="config-grid">
            <article v-for="item in mcpServers" :key="item.id" class="config-item">
              <div class="config-item-icon config-item-icon--skill"><AppIcon name="layers" /></div>
              <div class="config-item-body">
                <div class="config-item-title"><h2>{{ item.name }}</h2><span class="index-badge" :class="item.connected ? 'index-badge--ready' : 'tag--muted'">{{ item.connected ? '已连接' : '未连接' }}</span></div>
                <p>{{ item.baseUrl }}</p>
                <div class="config-tags"><span>{{ item.toolCount }} 个工具</span><span>{{ item.hasAuth ? '凭据已保存' : '缺少凭据' }}</span></div>
              </div>
              <div class="config-actions">
                <button class="button button--soft" type="button" :disabled="saving" @click="changeMcpConnection(item)">{{ item.connected ? '断开' : '连接并发现' }}</button>
                <button class="icon-button" type="button" aria-label="编辑 MCP 服务" @click="editMcp(item)"><AppIcon name="edit" /></button>
                <button class="icon-button icon-button--danger" type="button" aria-label="删除 MCP 服务" @click="deleteTarget = { type: 'mcp', item }"><AppIcon name="trash" /></button>
              </div>
              <div v-if="toolsForServer(item.id).length" class="mcp-tool-list" aria-label="已发现工具">
                <article v-for="tool in toolsForServer(item.id)" :key="tool.id" class="mcp-tool-item">
                  <strong>{{ tool.name }}</strong>
                  <p>{{ tool.description }}</p>
                  <small>输入：{{ schemaSummary(tool.inputSchema) }}（* 为必填）</small>
                </article>
              </div>
            </article>
            <div v-if="!mcpServers.length" class="empty-state"><span><AppIcon name="layers" /></span><h3>还没有 MCP 服务</h3><p>登记服务并连接后，工具会出现在技能编辑器中。</p></div>
          </div>
          <section class="mcp-audit">
            <h2>最近工具调用</h2>
            <div class="mcp-audit-table" role="table" aria-label="MCP 调用审计">
              <div v-for="call in mcpCalls" :key="call.id" class="mcp-audit-row" role="row">
                <strong>{{ call.toolRef }}</strong><span :class="`tool-status tool-status--${call.status}`">{{ call.status }}</span><span>{{ call.detail }}</span><time>{{ new Date(call.createdAt).toLocaleString() }}</time>
              </div>
              <p v-if="!mcpCalls.length">暂无工具调用记录。</p>
            </div>
          </section>
        </div>
      </section>
    </div>

    <BaseModal v-if="assistantOpen" :title="assistantDraft.id ? '编辑智能体' : '新建智能体'" description="保存时会完整替换技能和讲义绑定。" wide @close="!saving && (assistantOpen = false)">
      <form id="assistant-form" class="config-form" @submit.prevent="saveAssistant">
        <label>名称<input v-model="assistantDraft.name" required maxlength="64" /></label>
        <label>角色提示词<textarea v-model="assistantDraft.promptText" rows="6" maxlength="2000" placeholder="留空时使用平台默认教学助手角色" /></label>
        <fieldset><legend>绑定技能</legend><div class="option-grid"><label v-for="item in skills" :key="item.id"><input v-model="assistantDraft.skillIds" type="checkbox" :value="item.id" /><span><strong>{{ item.name }}</strong><small>{{ item.triggerText }}</small></span></label><p v-if="!skills.length">暂无可绑定技能</p></div></fieldset>
        <fieldset><legend>绑定 READY 讲义</legend><div class="option-grid"><label v-for="item in readyMaterials" :key="item.id"><input v-model="assistantDraft.materialIds" type="checkbox" :value="item.id" /><span><strong>{{ item.title }}</strong><small>{{ item.originalFilename || '虚拟材料' }}</small></span></label><p v-if="!readyMaterials.length">暂无已完成索引的讲义</p></div></fieldset>
        <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      </form>
      <template #footer><button class="button button--secondary" type="button" :disabled="saving" @click="assistantOpen = false">取消</button><button class="button button--primary" form="assistant-form" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存配置' }}</button></template>
    </BaseModal>

    <BaseModal v-if="skillOpen" :title="skillDraft.id ? '编辑技能' : '新建技能'" description="填写触发规则和执行行为，保存时仍由服务端统一校验。" wide @close="!saving && (skillOpen = false)">
      <form id="skill-form" class="config-form" @submit.prevent="saveSkill">
        <div class="skill-editor-toolbar"><span>结构化技能定义</span><button class="button button--soft" type="button" @click="importInput?.click()"><AppIcon name="upload" :size="15" />导入文本</button><input ref="importInput" class="sr-only" type="file" accept="text/plain,.txt,.md" @change="importSkill" /></div>
        <div class="skill-form-grid">
          <label>技能名称<input v-model="skillDraft.name" required maxlength="32" placeholder="例如：解题引导" /></label>
          <label>触发关键词 / 适用条件<input v-model="skillDraft.triggerText" required maxlength="120" placeholder="多个关键词用逗号分隔；解题引导可填写“各类习题”" /></label>
          <label class="skill-form-wide">资料范围（可选）<input v-model="skillDraft.materialScope" maxlength="32" placeholder="按讲义标题进一步筛选；留空表示全部绑定讲义" /></label>
          <label class="skill-form-wide">行为说明<textarea v-model="skillDraft.instructions" rows="7" required maxlength="2000" placeholder="说明命中该技能后助手应如何回答。" /></label>
        </div>
        <label class="switch-field"><input v-model="skillDraft.enabled" type="checkbox" /><span>创建后立即启用</span></label>
        <fieldset class="skill-tools"><legend>绑定工具</legend>
          <label><input v-model="skillDraft.toolRefs" type="checkbox" value="knowledge_search" /><span><strong>知识检索</strong><small>回答前检索智能体绑定的讲义（knowledge_search）</small></span></label>
          <label v-for="tool in selectableToolRefs" :key="tool.ref"><input v-model="skillDraft.toolRefs" type="checkbox" :value="tool.ref" /><span><strong>{{ tool.ref }}</strong><small>{{ tool.description }}</small></span></label>
          <p v-if="!mcpTools.length">连接 MCP 服务后可选择外部工具。</p>
        </fieldset>
        <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      </form>
      <template #footer><button class="button button--secondary" type="button" :disabled="saving" @click="skillOpen = false">取消</button><button class="button button--primary" form="skill-form" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存技能' }}</button></template>
    </BaseModal>

    <BaseModal v-if="mcpOpen" :title="mcpDraft.id ? '编辑 MCP 服务' : '登记 MCP 服务'" description="凭据只提交给后端；留空表示编辑时保持原值。" @close="!saving && (mcpOpen = false)">
      <form id="mcp-form" class="config-form" @submit.prevent="saveMcp">
        <label>服务名<input v-model="mcpDraft.name" required maxlength="32" placeholder="campus-tools" /></label>
        <label>Base URL<input v-model="mcpDraft.baseUrl" required maxlength="512" placeholder="http://mcp-demo:8090" /></label>
        <label>Bearer Token<input v-model="mcpDraft.token" :required="!mcpDraft.id" maxlength="512" type="password" autocomplete="new-password" placeholder="编辑时留空表示保持原值" /></label>
        <p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      </form>
      <template #footer><button class="button button--secondary" type="button" :disabled="saving" @click="mcpOpen = false">取消</button><button class="button button--primary" form="mcp-form" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存服务' }}</button></template>
    </BaseModal>

    <BaseModal v-if="deleteTarget" :title="deleteTarget.type === 'assistant' ? '删除智能体' : deleteTarget.type === 'skill' ? '删除技能' : '删除 MCP 服务'" :description="deleteTarget.type === 'assistant' ? '只有尚未产生历史消息的智能体可以删除。' : deleteTarget.type === 'skill' ? '此操作会解除所有智能体对该技能的绑定。' : '已有调用审计的服务不能删除，可以改为断开。'" @close="!saving && (deleteTarget = null)">
      <div class="delete-warning"><span><AppIcon name="trash" /></span><p v-if="deleteTarget.type === 'assistant'">确定删除“{{ deleteTarget.item.name }}”吗？零消息空会话会自动清理；如果已有真实消息，系统会拒绝删除并建议停用。</p><p v-else-if="deleteTarget.type === 'skill'">确定删除“{{ deleteTarget.item.name }}”吗？讲义和智能体本身不会被删除。</p><p v-else>确定删除“{{ deleteTarget.item.name }}”吗？如果已有历史审计，系统会拒绝并保留全部数据。</p></div><p v-if="error" class="feedback feedback--error" role="alert">{{ error }}</p>
      <template #footer><button class="button button--secondary" type="button" :disabled="saving" @click="deleteTarget = null">取消</button><button class="button button--danger" type="button" :disabled="saving" @click="confirmDelete">确认删除</button></template>
    </BaseModal>
  </AppShell>
</template>
