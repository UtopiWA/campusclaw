import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../services/api.js'

const { agents, authState } = vi.hoisted(() => ({
  agents: {
    listAssistants: vi.fn(), getAssistant: vi.fn(), createAssistant: vi.fn(), updateAssistant: vi.fn(),
    listSkills: vi.fn(), createSkill: vi.fn(), updateSkill: vi.fn(), deleteSkill: vi.fn(),
    listMaterials: vi.fn(),
  },
  authState: { user: { username: 'teacher-a', role: 'teacher', className: '班级 A' }, loaded: true },
}))

vi.mock('../services/agents.js', () => agents)
vi.mock('../services/auth.js', () => ({ authState, logout: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn() }) }))

import AgentConfigView from './AgentConfigView.vue'

const global = { stubs: { RouterLink: { template: '<a><slot /></a>' } } }

describe('AgentConfigView', () => {
  beforeEach(() => {
    Object.values(agents).forEach((mock) => mock.mockReset())
    agents.listAssistants.mockResolvedValue([])
    agents.listSkills.mockResolvedValue([])
    agents.listMaterials.mockResolvedValue([])
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

  it('keeps skill source as plain text when a save fails', async () => {
    agents.createSkill.mockRejectedValue(new Error('第 2 行存在未知字段'))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')
    const source = '<img src=x onerror=alert(1)>\nunknown: value'
    await wrapper.find('.skill-editor').setValue(source)
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.find('.skill-editor').element.value).toBe(source)
    expect(wrapper.text()).toContain('第 2 行存在未知字段')
    expect(wrapper.find('.modal-body img').exists()).toBe(false)
  })

  it.each([400, 403, 404])('shows a %i skill save error without discarding source', async (status) => {
    agents.createSkill.mockRejectedValue(new ApiError(status, `保存失败 ${status}`))
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')
    await wrapper.find('.skill-editor').setValue('需要保留的技能原文')
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(`保存失败 ${status}`)
    expect(wrapper.find('.skill-editor').element.value).toBe('需要保留的技能原文')
  })

  it('imports a UTF-8 skill file and saves the decoded source', async () => {
    agents.createSkill.mockResolvedValue({ id: 5 })
    const wrapper = mount(AgentConfigView, { global })
    await flushPromises()
    await wrapper.findAll('.config-tabs button')[1].trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')
    const source = '名称：阅读引导\n适用条件：阅读\n启用：是\n\n逐步提示。'
    const file = new File([source], 'reading.txt', { type: 'text/plain' })
    Object.defineProperty(file, 'arrayBuffer', {
      value: async () => new TextEncoder().encode(source).buffer,
    })
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })

    await input.trigger('change')
    await wrapper.find('#skill-form').trigger('submit')
    await flushPromises()

    expect(agents.createSkill).toHaveBeenCalledWith(source)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('requires confirmation before deleting a skill', async () => {
    agents.listSkills.mockResolvedValue([{
      id: 5, name: '阅读引导', triggerText: '阅读', materialScope: '', instructions: '逐步提示。',
      sourceText: 'source', enabled: true,
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
})
