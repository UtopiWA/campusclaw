import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../services/api.js'

const { agents, mcp, authState } = vi.hoisted(() => ({
  agents: {
    listAssistants: vi.fn(), getAssistant: vi.fn(), createAssistant: vi.fn(), updateAssistant: vi.fn(),
    deleteAssistant: vi.fn(),
    listSkills: vi.fn(), createSkill: vi.fn(), updateSkill: vi.fn(), deleteSkill: vi.fn(),
    listMaterials: vi.fn(),
  },
  mcp: {
    listMcpServers: vi.fn(), createMcpServer: vi.fn(), updateMcpServer: vi.fn(), deleteMcpServer: vi.fn(),
    connectMcpServer: vi.fn(), disconnectMcpServer: vi.fn(), listMcpTools: vi.fn(), listMcpToolCalls: vi.fn(),
  },
  authState: { user: { username: 'teacher-a', role: 'teacher', className: '班级 A' }, loaded: true },
}))

vi.mock('../services/agents.js', () => agents)
vi.mock('../services/mcp.js', () => mcp)
vi.mock('../services/auth.js', () => ({ authState, logout: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn() }) }))

import AgentConfigView from './AgentConfigView.vue'

const global = { stubs: { RouterLink: { template: '<a><slot /></a>' } } }

async function fillSkillForm(wrapper, values = {}) {
  const inputs = wrapper.findAll('.skill-form-grid input')
  await inputs[0].setValue(values.name || '阅读引导')
  await inputs[1].setValue(values.triggerText || '阅读, 概括')
  await inputs[2].setValue(values.materialScope || '')
  await wrapper.find('.skill-form-grid textarea').setValue(values.instructions || '逐步提示并引用讲义。')
}

describe('AgentConfigView', () => {
  beforeEach(() => {
    Object.values(agents).forEach((mock) => mock.mockReset())
    Object.values(mcp).forEach((mock) => mock.mockReset())
    agents.listAssistants.mockResolvedValue([])
    agents.listSkills.mockResolvedValue([])
    agents.listMaterials.mockResolvedValue([])
    mcp.listMcpServers.mockResolvedValue([])
    mcp.listMcpToolCalls.mockResolvedValue([])
  })

  it('submits complete de-duplicated assistant bindings and only offers READY materials', async () => {
    agents.listSkills.mockResolvedValue([
      { id: 2, name: '引导', triggerText: '方程', sourceText: 'source' },
      { id: 3, name: '归纳', triggerText: '总结', sourceText: 'source' },
    ])
    agents.listMaterials.mockResolvedValue([
      { id: 7, title: '可用讲义', indexStatus: 'READY' },
      { id: 8, title: '解析中讲义', indexStatus: 'INDEXING' },
    ])
    agents.createAssistant.mockResolvedValue({ id: 1 })
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()

    await wrapper.find('.page-heading .button--primary').trigger('click')
    const form = wrapper.find('#assistant-form')
    await form.find('input:not([type])').setValue('数学助手')
    const checkboxes = form.findAll('.option-grid input[type="checkbox"]')
    expect(wrapper.text()).toContain('可用讲义')
    expect(wrapper.text()).not.toContain('解析中讲义')
    await checkboxes[0].setValue(true)
    await checkboxes[1].setValue(true)
    await checkboxes[2].setValue(true)
    await form.trigger('submit')
    await flushPromises()

    expect(agents.createAssistant).toHaveBeenCalledWith(expect.objectContaining({
      name: '数学助手', skillIds: [2, 3], materialIds: [7], enabled: true,
    }))
  })

  it('keeps the complete assistant draft when saving fails', async () => {
    agents.createAssistant.mockRejectedValue(new Error('本班已存在同名智能体'))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.find('.page-heading .button--primary').trigger('click')
    await wrapper.find('#assistant-form input:not([type])').setValue('保留草稿')
    await wrapper.find('#assistant-form textarea').setValue('不要丢失的提示词')
    await wrapper.find('#assistant-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.find('#assistant-form input:not([type])').element.value).toBe('保留草稿')
    expect(wrapper.find('#assistant-form textarea').element.value).toBe('不要丢失的提示词')
    expect(wrapper.text()).toContain('本班已存在同名智能体')
  })

  it('keeps structured skill fields as plain text when a save fails', async () => {
    agents.createSkill.mockRejectedValue(new Error('第 2 行存在未知字段'))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')
    const name = '<img src=x onerror=alert(1)>'
    await fillSkillForm(wrapper, { name })
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.findAll('.skill-form-grid input')[0].element.value).toBe(name)
    expect(wrapper.text()).toContain('第 2 行存在未知字段')
    expect(wrapper.find('.modal-body img').exists()).toBe(false)
  })

  it.each([400, 403, 404])('shows a %i skill save error without discarding fields', async (status) => {
    agents.createSkill.mockRejectedValue(new ApiError(status, `保存失败 ${status}`))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')
    await fillSkillForm(wrapper, { instructions: '需要保留的行为说明' })
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(`保存失败 ${status}`)
    expect(wrapper.find('.skill-form-grid textarea').element.value).toBe('需要保留的行为说明')
  })

  it('imports a UTF-8 skill file and saves the decoded source', async () => {
    agents.createSkill.mockResolvedValue({ id: 5 })
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')
    const source = '名称：阅读引导\n适用条件：阅读\n资料：语文\n工具：knowledge_search\n启用：是\n\n逐步提示。'
    const file = new File([source], 'reading.txt', { type: 'text/plain' })
    Object.defineProperty(file, 'arrayBuffer', {
      value: async () => new TextEncoder().encode(source).buffer,
    })
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })

    await input.trigger('change')
    expect(wrapper.findAll('.skill-form-grid input')[0].element.value).toBe('阅读引导')
    expect(wrapper.findAll('.skill-form-grid input')[2].element.value).toBe('语文')
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(agents.createSkill).toHaveBeenCalledWith(source)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('builds the server skill format from friendly fields and tool binding', async () => {
    agents.createSkill.mockResolvedValue({ id: 6 })
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')
    await fillSkillForm(wrapper, { name: '解题引导', triggerText: '方程，求解', materialScope: '代数' })
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(agents.createSkill).toHaveBeenCalledWith([
      '名称：解题引导', '适用条件：方程，求解', '资料：代数', '工具：knowledge_search', '启用：是', '',
      '逐步提示并引用讲义。',
    ].join('\n'))
  })

  it('shows bound tools directly on each skill card', async () => {
    agents.listSkills.mockResolvedValue([{
      id: 5, name: '数学引导', triggerText: '方程', materialScope: '代数', instructions: '逐步提示。',
      toolRefs: ['knowledge_search', 'formula_hint@demo'], enabled: true,
    }])
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')

    const summary = wrapper.find('.skill-tool-summary')
    expect(summary.text()).toContain('知识检索 · knowledge_search')
    expect(summary.text()).toContain('formula_hint@demo')
  })

  it('loads an existing skill into fields and submits a complete update', async () => {
    agents.listSkills.mockResolvedValue([{
      id: 5, name: '阅读引导', triggerText: '阅读', materialScope: '语文', toolName: 'knowledge_search',
      instructions: '原行为。', sourceText: 'source', enabled: false,
    }])
    agents.updateSkill.mockResolvedValue({ id: 5 })
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('[aria-label="编辑技能"]').trigger('click')

    expect(wrapper.findAll('.skill-form-grid input')[0].element.value).toBe('阅读引导')
    expect(wrapper.find('.switch-field input').element.checked).toBe(false)
    await wrapper.find('.skill-form-grid textarea').setValue('更新后的行为。')
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(agents.updateSkill).toHaveBeenCalledWith(5, [
      '名称：阅读引导', '适用条件：阅读', '资料：语文', '工具：knowledge_search', '启用：否', '', '更新后的行为。',
    ].join('\n'))
  })

  it('toggles a skill with its complete existing configuration', async () => {
    agents.listSkills.mockResolvedValue([{
      id: 5, name: '阅读引导', triggerText: '阅读', materialScope: '语文', toolName: 'knowledge_search',
      instructions: '逐步提示。', sourceText: 'source', enabled: true,
    }])
    agents.updateSkill.mockResolvedValue({ id: 5, enabled: false })
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('[aria-label="停用技能 阅读引导"]').setValue(false)
    await flushPromises()

    expect(agents.updateSkill).toHaveBeenCalledWith(5, [
      '名称：阅读引导', '适用条件：阅读', '资料：语文', '工具：knowledge_search', '启用：否', '', '逐步提示。',
    ].join('\n'))
    expect(wrapper.text()).toContain('已停用')
  })

  it('keeps the old skill state when toggling fails', async () => {
    agents.listSkills.mockResolvedValue([{
      id: 5, name: '阅读引导', triggerText: '阅读', materialScope: '', toolName: 'knowledge_search',
      instructions: '逐步提示。', sourceText: 'source', enabled: true,
    }])
    agents.updateSkill.mockRejectedValue(new Error('技能状态保存失败'))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('[aria-label="停用技能 阅读引导"]').setValue(false)
    await flushPromises()

    expect(wrapper.find('[aria-label="停用技能 阅读引导"]').element.checked).toBe(true)
    expect(wrapper.text()).toContain('技能状态保存失败')
    expect(wrapper.text()).toContain('已启用')
  })

  it('requires confirmation before deleting a skill', async () => {
    agents.listSkills.mockResolvedValue([{
      id: 5, name: '阅读引导', triggerText: '阅读', materialScope: '', instructions: '逐步提示。',
      sourceText: 'source', toolName: 'knowledge_search', enabled: true,
    }])
    agents.deleteSkill.mockResolvedValue(null)
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('[aria-label="删除技能"]').trigger('click')

    expect(agents.deleteSkill).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('确定删除“阅读引导”吗')
    await wrapper.find('.modal-footer .button--danger').trigger('click')
    await flushPromises()
    expect(agents.deleteSkill).toHaveBeenCalledWith(5)
  })

  it('deletes an unused assistant after confirmation', async () => {
    agents.listAssistants.mockResolvedValue([{
      id: 8, name: '临时助手', enabled: true, promptText: '', skillIds: [], materialIds: [],
    }])
    agents.deleteAssistant.mockResolvedValue(null)
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()

    await wrapper.find('[aria-label="删除智能体 临时助手"]').trigger('click')
    expect(wrapper.text()).toContain('只有尚未产生历史消息的智能体可以删除')
    await wrapper.find('.modal-footer .button--danger').trigger('click')
    await flushPromises()

    expect(agents.deleteAssistant).toHaveBeenCalledWith(8)
    expect(agents.deleteSkill).not.toHaveBeenCalled()
  })

  it('toggles an assistant from the list with its complete configuration', async () => {
    agents.listAssistants.mockResolvedValue([{
      id: 8, name: '数学助手', enabled: true, promptText: '引导思考', skillIds: [2], materialIds: [7],
    }])
    agents.updateAssistant.mockResolvedValue({ id: 8, enabled: false })
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()

    const toggle = wrapper.findAll('.config-actions .button').find((button) => button.text() === '停用')
    await toggle.trigger('click')
    await flushPromises()

    expect(agents.updateAssistant).toHaveBeenCalledWith(8, {
      name: '数学助手', promptText: '引导思考', enabled: false, skillIds: [2], materialIds: [7],
    })
    expect(wrapper.text()).toContain('智能体“数学助手”已停用')
  })

  it('restores the assistant state when a quick toggle fails', async () => {
    agents.listAssistants.mockResolvedValue([{
      id: 8, name: '数学助手', enabled: true, promptText: '', skillIds: [], materialIds: [],
    }])
    agents.updateAssistant.mockRejectedValue(new Error('智能体状态保存失败'))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()

    const toggle = wrapper.findAll('.config-actions .button').find((button) => button.text() === '停用')
    await toggle.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('智能体状态保存失败')
    expect(wrapper.text()).toContain('已启用')
    expect(wrapper.findAll('.config-actions .button').some((button) => button.text() === '停用')).toBe(true)
  })

  it('separates MCP tools from audit metrics and shows usernames, Chinese tool names, and durations', async () => {
    mcp.listMcpServers.mockResolvedValue([{
      id: 21, name: 'demo', baseUrl: 'http://mcp-demo:8090/mcp', connected: true,
      hasAuth: true, toolCount: 1,
    }])
    mcp.listMcpTools.mockResolvedValue([{
      id: 31, name: 'calculator', description: '安全计算数学表达式。',
      inputSchema: { properties: { expression: {} }, required: ['expression'] },
    }])
    mcp.listMcpToolCalls.mockResolvedValue([{
      id: 41, userId: 7, userName: 'student-a', conversationId: 19,
      toolRef: 'calculator@demo', status: 'ok', detail: '(2 + 3) * 4 = 20', durationMs: 37,
      createdAt: '2026-10-16T10:00:00Z',
    }])

    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[2].trigger('click')

    expect(wrapper.find('.mcp-services-panel').exists()).toBe(true)
    expect(wrapper.find('.mcp-audit').exists()).toBe(true)
    expect(wrapper.text()).toContain('计算器')
    expect(wrapper.text()).toContain('calculator')
    expect(wrapper.text()).toContain('安全计算数学表达式。')
    expect(wrapper.text()).toContain('expression*')
    expect(wrapper.text()).toContain('student-a')
    expect(wrapper.text()).toContain('37 ms')
    expect(wrapper.text()).not.toContain('用户 #7')
    expect(wrapper.text()).not.toContain('会话 #19')
    expect(wrapper.text()).not.toContain('(2 + 3) * 4 = 20')
    expect(wrapper.text()).not.toContain('secret-token')
  })

  it('submits an MCP token once and never repopulates it while editing', async () => {
    const server = { id: 21, name: 'demo', baseUrl: 'http://mcp-demo:8090/mcp', connected: false, hasAuth: true, toolCount: 0 }
    mcp.listMcpServers.mockResolvedValue([server])
    mcp.createMcpServer.mockResolvedValue(server)
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[2].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')

    const form = wrapper.find('#mcp-form')
    const inputs = form.findAll('input')
    await inputs[0].setValue('demo-two')
    await inputs[1].setValue('http://mcp-demo:8090')
    await inputs[2].setValue('secret-token')
    await form.trigger('submit')
    await flushPromises()

    expect(mcp.createMcpServer).toHaveBeenCalledWith({
      name: 'demo-two', baseUrl: 'http://mcp-demo:8090', token: 'secret-token',
    })
    await wrapper.find('[aria-label="编辑 MCP 服务"]').trigger('click')
    expect(wrapper.find('#mcp-form input[type="password"]').element.value).toBe('')
  })

  it('keeps the assistant delete dialog open when history causes a conflict', async () => {
    agents.listAssistants.mockResolvedValue([{
      id: 9, name: '已有会话助手', enabled: true, promptText: '', skillIds: [], materialIds: [],
    }])
    agents.deleteAssistant.mockRejectedValue(new ApiError(409, '该智能体已有会话记录，请停用智能体以保留历史会话'))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()

    await wrapper.find('[aria-label="删除智能体 已有会话助手"]').trigger('click')
    await wrapper.find('.modal-footer .button--danger').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('请停用智能体以保留历史会话')
  })
})
