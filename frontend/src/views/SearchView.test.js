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

function buttonWithText(wrapper, text) {
  return wrapper.findAll('button').find((button) => button.text().includes(text))
}

describe('SearchView', () => {
  beforeEach(() => {
    searchKnowledge.mockReset()
    askKnowledge.mockReset()
    searchKnowledge.mockResolvedValue({ mode: 'hybrid', message: null, hits: [] })
  })

  it('renders direct search in the conversation and numbers visible results from one', async () => {
    searchKnowledge.mockResolvedValue({
      mode: 'hybrid', message: null, hits: [{
        materialId: 1, materialTitle: '安全讲义', knowledgeEntryId: 2, chunkId: 803, chunkIndex: 42,
        startOffset: 0, endOffset: 8, excerpt: '<img src=x onerror=alert(1)>', rank: 9,
        finalScore: 0.03, keywordScore: 1.2, keywordRank: 1, vectorScore: 0.8, vectorRank: 2,
      }],
    })
    const wrapper = mount(SearchView, { global })
    expect(wrapper.text()).toContain('今天想从讲义中了解什么')
    await buttonWithText(wrapper, '直接检索').trigger('click')
    await wrapper.find('textarea').setValue('课堂提问')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(searchKnowledge).toHaveBeenCalledWith('课堂提问', 'hybrid')
    expect(wrapper.find('.chat-hit-number').text()).toBe('1')
    expect(wrapper.find('.chat-hit-title').text()).toContain('引用 1')
    expect(wrapper.text()).not.toContain('切片 43')
    expect(wrapper.text()).not.toContain('Chunk #803')
    expect(wrapper.find('.chat-hit-main p').text()).toContain('<img')
    expect(wrapper.find('.chat-hit-main img').exists()).toBe(false)
    expect(wrapper.find('.chat-hit a').attributes('data-to')).toBe('/materials?material=1')

    for (const mode of ['关键词', '语义']) {
      await buttonWithText(wrapper, mode).trigger('click')
      await wrapper.find('textarea').setValue(`${mode}查询`)
      await wrapper.find('form').trigger('submit')
      await flushPromises()
    }
    expect(searchKnowledge).toHaveBeenNthCalledWith(2, '关键词查询', 'keyword')
    expect(searchKnowledge).toHaveBeenNthCalledWith(3, '语义查询', 'vector')
  })

  it('keeps multiple grounded turns and displays citation order instead of chunk numbers', async () => {
    askKnowledge
      .mockResolvedValueOnce({ answer: '第一轮结论 [1]', citations: [{
        number: 17, materialId: 1, materialTitle: '讲义甲', chunkId: 902, chunkIndex: 51,
        excerpt: '<strong>可直接核对的依据片段</strong>',
      }] })
      .mockResolvedValueOnce({ answer: '第二轮结论 [1]', citations: [{
        number: 8, materialId: 2, materialTitle: '讲义乙', chunkId: 999, chunkIndex: 76, excerpt: '第二条依据',
      }] })
    const wrapper = mount(SearchView, { global })

    await wrapper.find('textarea').setValue('第一个问题')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(askKnowledge).toHaveBeenNthCalledWith(1, '第一个问题', [])
    expect(wrapper.find('.chat-citation-heading').text()).toContain('[1] 讲义甲')
    expect(wrapper.text()).not.toContain('[17]')
    expect(wrapper.text()).not.toContain('切片 52')
    expect(wrapper.find('.chat-citation p').text()).toContain('<strong>可直接核对的依据片段</strong>')
    expect(wrapper.find('.chat-citation p strong').exists()).toBe(false)

    await wrapper.find('textarea').setValue('继续追问')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(askKnowledge).toHaveBeenNthCalledWith(2, '继续追问', [
      { role: 'user', content: '第一个问题' },
      { role: 'assistant', content: '第一轮结论 [1]' },
    ])
    expect(wrapper.findAll('.chat-message--user')).toHaveLength(2)
    expect(wrapper.findAll('.chat-message--assistant')).toHaveLength(2)
    expect(wrapper.findAll('.chat-citation-heading')[1].text()).toContain('[1] 讲义乙')
  })

  it('validates input, prevents duplicate submission and masks dependency errors', async () => {
    const wrapper = mount(SearchView, { global })
    await wrapper.find('form').trigger('submit')
    expect(askKnowledge).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入 1～1000 个字符的问题')

    let finishRequest
    askKnowledge.mockReturnValueOnce(new Promise((resolve) => { finishRequest = resolve }))
    await wrapper.find('textarea').setValue('并发提交')
    await wrapper.find('form').trigger('submit')
    await wrapper.find('form').trigger('submit')
    expect(askKnowledge).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.chat-send').attributes('disabled')).toBeDefined()
    finishRequest({ answer: '完成', citations: [] })
    await flushPromises()

    askKnowledge.mockRejectedValueOnce(new ApiError(503, '内部依赖地址'))
    await wrapper.find('textarea').setValue('再次提问')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('检索依赖暂时不可用，请稍后重试')
    expect(wrapper.text()).not.toContain('内部依赖地址')
  })
})
