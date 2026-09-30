import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../services/api.js'

const { searchKnowledge, askKnowledge, authState } = vi.hoisted(() => ({
  searchKnowledge: vi.fn(),
  askKnowledge: vi.fn(),
  authState: { user: { username: 'teacher-a', role: 'teacher', className: '班级 A' }, loaded: true },
}))

vi.mock('../services/retrieval.js', () => ({ searchKnowledge, askKnowledge }))
vi.mock('../services/auth.js', () => ({ authState, logout: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn() }) }))

import SearchView from './SearchView.vue'

const global = { stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } } }

describe('SearchView', () => {
  beforeEach(() => {
    searchKnowledge.mockReset()
    askKnowledge.mockReset()
    searchKnowledge.mockResolvedValue({ mode: 'hybrid', message: null, hits: [] })
  })

  it('defaults to hybrid and submits all three modes without rendering excerpts as HTML', async () => {
    searchKnowledge.mockResolvedValue({
      mode: 'hybrid', message: null, hits: [{
        materialId: 1, materialTitle: '安全讲义', knowledgeEntryId: 2, chunkId: 3, chunkIndex: 0,
        startOffset: 0, endOffset: 8, excerpt: '<img src=x onerror=alert(1)>', rank: 1,
        finalScore: 0.03, keywordScore: 1.2, keywordRank: 1, vectorScore: 0.8, vectorRank: 2,
      }],
    })
    const wrapper = mount(SearchView, { global })
    expect(wrapper.find('.nav-item.active').text()).toContain('知识检索')
    await wrapper.find('textarea').setValue('课堂提问')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(searchKnowledge).toHaveBeenCalledWith('课堂提问', 'hybrid')
    expect(wrapper.find('.hit-excerpt').text()).toContain('<img')
    expect(wrapper.find('.hit-excerpt img').exists()).toBe(false)
    expect(wrapper.find('.hit-card a').attributes('data-to')).toBe('/materials?material=1')

    for (const mode of ['keyword', 'vector']) {
      await wrapper.find(`input[value="${mode}"]`).setValue()
      await wrapper.find('form').trigger('submit')
      await flushPromises()
      expect(searchKnowledge).toHaveBeenLastCalledWith('课堂提问', mode)
    }
  })

  it('distinguishes empty evidence from grounded citations', async () => {
    searchKnowledge.mockResolvedValueOnce({ mode: 'hybrid', message: '资料中未找到相关内容', hits: [] })
    const wrapper = mount(SearchView, { global })
    await wrapper.find('textarea').setValue('不存在的资料')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('资料中未找到相关内容')

    askKnowledge.mockResolvedValueOnce({ answer: '结论 [1]', citations: [{
      number: 1, materialId: 1, materialTitle: '讲义', chunkId: 2, chunkIndex: 0, excerpt: '依据',
    }] })
    await wrapper.find('.ask-action button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('[1] 讲义')
  })

  it('validates input, prevents duplicate submission and distinguishes 400 from 503', async () => {
    const wrapper = mount(SearchView, { global })
    await wrapper.find('form').trigger('submit')
    expect(searchKnowledge).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入 1～1000 个字符的问题')

    let finishRequest
    searchKnowledge.mockReturnValueOnce(new Promise((resolve) => { finishRequest = resolve }))
    await wrapper.find('textarea').setValue('并发提交')
    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')
    expect(searchKnowledge).toHaveBeenCalledTimes(1)
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    finishRequest({ mode: 'hybrid', message: '资料中未找到相关内容', hits: [] })
    await flushPromises()

    searchKnowledge.mockRejectedValueOnce(new ApiError(400, '查询参数不合法'))
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('查询参数不合法')

    searchKnowledge.mockRejectedValueOnce(new ApiError(503, '内部依赖地址'))
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('检索依赖暂时不可用，请稍后重试')
    expect(wrapper.text()).not.toContain('内部依赖地址')
  })
})
