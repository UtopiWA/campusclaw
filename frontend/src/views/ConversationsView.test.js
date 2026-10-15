import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { state, authState } = vi.hoisted(() => ({
  state: {
    route: { params: { id: '9' } },
    push: vi.fn(),
    listAssistants: vi.fn(),
    listConversations: vi.fn(),
    getConversation: vi.fn(),
    createConversation: vi.fn(),
    deleteConversation: vi.fn(),
    streamConversationMessage: vi.fn(),
  },
  authState: { user: { username: 'student-a', role: 'student', className: '班级 A' }, loaded: true },
}))

vi.mock('../services/agents.js', () => ({ listAssistants: state.listAssistants }))
vi.mock('../services/conversations.js', () => ({
  listConversations: state.listConversations,
  getConversation: state.getConversation,
  createConversation: state.createConversation,
  deleteConversation: state.deleteConversation,
  streamConversationMessage: state.streamConversationMessage,
}))
vi.mock('../services/auth.js', () => ({ authState, logout: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => state.route,
  useRouter: () => ({ push: state.push, replace: vi.fn() }),
}))

import ConversationsView from './ConversationsView.vue'

const global = { stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } } }
const detail = () => ({
  id: 9, title: '函数追问', assistantId: 3, assistantName: '数学助手', messages: [{
    id: 4, role: 'assistant', content: '已有答案 [1]', skillName: '解题引导', status: 'FAILED',
    citations: [{ number: 1, materialId: 7, materialTitle: '函数讲义', chunkId: 42, excerpt: '<b>安全片段</b>' }],
  }],
})

describe('ConversationsView', () => {
  beforeEach(() => {
    Object.values(state).filter((value) => typeof value?.mockReset === 'function').forEach((mock) => mock.mockReset())
    state.route.params = { id: '9' }
    state.listAssistants.mockResolvedValue([{ id: 3, name: '数学助手', enabled: true }])
    state.listConversations.mockResolvedValue([{ id: 9, title: '函数追问', assistantName: '数学助手' }])
    state.getConversation.mockResolvedValue(detail())
  })

  it('restores server messages and renders safe server-numbered citations', async () => {
    const wrapper = mount(ConversationsView, { global, attachTo: document.body })
    await flushPromises()

    expect(wrapper.text()).toContain('已有答案 [1]')
    expect(wrapper.text()).toContain('[1] 函数讲义')
    expect(wrapper.find('.chat-citation p').text()).toBe('<b>安全片段</b>')
    expect(wrapper.find('.chat-citation b').exists()).toBe(false)
    expect(wrapper.find('.chat-citation a').attributes('data-to')).toBe('/materials?material=7&chunk=42')
    expect(wrapper.text()).toContain('回答已中断')
    await wrapper.find('.chat-inline-citation').trigger('click')
    expect(document.activeElement).toBe(wrapper.find('.chat-citation').element)
    await wrapper.find('.conversation-menu').trigger('click')
    expect(wrapper.find('.conversation-page').classes()).toContain('conversation-page--collapsed')
    wrapper.unmount()
  })

  it('only lists retrieval candidates that are actually cited by the answer', async () => {
    state.getConversation.mockResolvedValue({
      ...detail(), messages: [{
        id: 15, role: 'assistant', content: '只采用第二条 [2]', status: 'COMPLETED',
        citations: [
          { number: 1, materialId: 7, materialTitle: '未采用讲义', chunkId: 41, excerpt: '不应显示' },
          { number: 2, materialId: 7, materialTitle: '采用讲义', chunkId: 42, excerpt: '需要显示' },
          { number: 3, materialId: 8, materialTitle: '另一未采用讲义', chunkId: 43, excerpt: '不应显示' },
        ],
      }],
    })
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()

    expect(wrapper.findAll('.chat-citation')).toHaveLength(1)
    expect(wrapper.text()).toContain('[2] 采用讲义')
    expect(wrapper.text()).not.toContain('未采用讲义')
  })

  it('does not show citation cards when the answer contains no valid marker', async () => {
    state.getConversation.mockResolvedValue({
      ...detail(), messages: [{
        id: 16, role: 'assistant', content: '这段回答没有引用标记', status: 'COMPLETED',
        citations: [{ number: 1, materialId: 7, materialTitle: '候选讲义', chunkId: 42, excerpt: '候选' }],
      }],
    })
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()

    expect(wrapper.find('.chat-citations').exists()).toBe(false)
  })

  it('accumulates delta text once and refreshes from the server after done', async () => {
    const animationFrame = vi.spyOn(window, 'requestAnimationFrame')
      .mockImplementation((callback) => { callback(0); return 1 })
    state.streamConversationMessage.mockImplementation(async (_id, _content, onEvent) => {
      await onEvent({ event: 'meta', data: { skillName: '解题引导', citations: [] } })
      await onEvent({ event: 'delta', data: { text: '第一段' } })
      await onEvent({ event: 'delta', data: { text: '第二段' } })
      await onEvent({ event: 'done', data: { messageId: 12 } })
      return { doneSeen: true }
    })
    state.getConversation
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce({ ...detail(), messages: [
        ...detail().messages,
        { id: 11, role: 'user', content: '继续解释', status: 'COMPLETED', citations: [] },
        { id: 12, role: 'assistant', content: '第一段第二段', status: 'COMPLETED', citations: [] },
      ] })
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()
    await wrapper.find('.conversation-composer-wrap textarea').setValue('继续解释')
    await wrapper.find('.conversation-composer-wrap form').trigger('submit')
    await flushPromises()

    expect(state.streamConversationMessage).toHaveBeenCalledWith(9, '继续解释', expect.any(Function))
    await vi.waitFor(() => expect(wrapper.text()).toContain('第一段第二段'))
    expect(wrapper.text()).not.toContain('第一段第一段')
    expect(state.getConversation).toHaveBeenCalledTimes(2)
    expect(animationFrame).toHaveBeenCalledTimes(6)
    animationFrame.mockRestore()
  })

  it('updates the DOM from the reactive temporary message before done arrives', async () => {
    const frames = []
    const animationFrame = vi.spyOn(window, 'requestAnimationFrame')
      .mockImplementation((callback) => { frames.push(callback); return frames.length })
    let finishStream
    state.streamConversationMessage.mockImplementation(async (_id, _content, onEvent) => {
      await onEvent({ event: 'meta', data: { skillName: null, citations: [] } })
      await onEvent({ event: 'delta', data: { text: '甲乙' } })
      await new Promise((resolve) => {
        finishStream = async () => {
          await onEvent({ event: 'done', data: { messageId: 20 } })
          resolve()
        }
      })
      return { doneSeen: true }
    })
    state.getConversation
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce({ ...detail(), messages: [
        ...detail().messages,
        { id: 19, role: 'user', content: '逐字显示', status: 'COMPLETED', citations: [] },
        { id: 20, role: 'assistant', content: '甲乙', status: 'COMPLETED', citations: [] },
      ] })
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()
    await wrapper.find('.conversation-composer-wrap textarea').setValue('逐字显示')
    await wrapper.find('.conversation-composer-wrap form').trigger('submit')
    await vi.waitFor(() => expect(frames).toHaveLength(1))

    frames.shift()(0)
    await flushPromises()
    expect(wrapper.findAll('.chat-answer').at(-1).text()).toContain('甲')
    expect(wrapper.findAll('.chat-answer').at(-1).text()).not.toContain('乙')
    expect(state.getConversation).toHaveBeenCalledTimes(1)

    frames.shift()(16)
    await finishStream()
    await vi.waitFor(() => expect(state.getConversation).toHaveBeenCalledTimes(2))
    expect(wrapper.findAll('.chat-answer').at(-1).text()).toContain('甲乙')
    animationFrame.mockRestore()
  })

  it('paints a large delta character by character and reveals citations only after done', async () => {
    const animationFrame = vi.spyOn(window, 'requestAnimationFrame')
      .mockImplementation((callback) => { callback(0); return 1 })
    const longDelta = '逐'.repeat(30)
    let wrapper
    let candidateVisibleBeforeDone = true
    state.streamConversationMessage.mockImplementation(async (_id, _content, onEvent) => {
      await onEvent({ event: 'meta', data: { skillName: null, citations: [
        { number: 1, materialId: 7, materialTitle: '候选讲义', chunkId: 42, excerpt: '候选片段' },
      ] } })
      await onEvent({ event: 'delta', data: { text: longDelta } })
      candidateVisibleBeforeDone = wrapper.text().includes('候选讲义')
      await onEvent({ event: 'done', data: { messageId: 18 } })
      return { doneSeen: true }
    })
    state.getConversation
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce({ ...detail(), messages: [
        { id: 17, role: 'user', content: '流式测试', status: 'COMPLETED', citations: [] },
        { id: 18, role: 'assistant', content: `${longDelta} [1]`, status: 'COMPLETED', citations: [
          { number: 1, materialId: 7, materialTitle: '最终讲义', chunkId: 42, excerpt: '最终片段' },
        ] },
      ] })
    wrapper = mount(ConversationsView, { global })
    await flushPromises()
    await wrapper.find('.conversation-composer-wrap textarea').setValue('流式测试')
    await wrapper.find('.conversation-composer-wrap form').trigger('submit')
    await flushPromises()

    expect(animationFrame).toHaveBeenCalledTimes(30)
    expect(candidateVisibleBeforeDone).toBe(false)
    expect(wrapper.text()).toContain('[1] 最终讲义')
    expect(wrapper.text()).not.toContain('候选讲义')
    animationFrame.mockRestore()
  })

  it('keeps partial text and offers retry when the stream disconnects without done', async () => {
    state.streamConversationMessage.mockImplementation(async (_id, _content, onEvent) => {
      await onEvent({ event: 'meta', data: { citations: [] } })
      await onEvent({ event: 'delta', data: { text: '已生成部分' } })
      return { doneSeen: false }
    })
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()
    await wrapper.find('.conversation-composer-wrap textarea').setValue('网络测试')
    await wrapper.find('.conversation-composer-wrap form').trigger('submit')
    await flushPromises()

    await vi.waitFor(() => expect(wrapper.text()).toContain('已生成部分'))
    expect(wrapper.text()).toContain('连接在回答完成前中断')
    expect(wrapper.find('.conversation-failed button').text()).toBe('重新发送问题')
  })

  it('shows the empty assistant state and can create a locked-assistant conversation', async () => {
    state.route.params = {}
    state.getConversation.mockReset()
    state.createConversation.mockResolvedValue({ id: 10, assistantId: 3 })
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()

    expect(wrapper.text()).toContain('选择智能体')
    await wrapper.find('.conversation-start').trigger('submit')
    await flushPromises()
    expect(state.createConversation).toHaveBeenCalledWith(3)
    expect(state.push).toHaveBeenCalledWith('/conversations/10')

    state.listAssistants.mockResolvedValue([])
    const emptyWrapper = mount(ConversationsView, { global })
    await flushPromises()
    expect(emptyWrapper.text()).toContain('当前班级没有可用的智能体')
    expect(emptyWrapper.find('.conversation-start').exists()).toBe(false)
  })

  it('keeps the assistant choice visible when conversation creation fails', async () => {
    state.route.params = {}
    state.getConversation.mockReset()
    state.createConversation.mockRejectedValue(new Error('创建会话失败'))
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()
    await wrapper.find('.conversation-start').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('创建会话失败')
    expect(wrapper.find('.conversation-start select').element.value).toBe('3')
    expect(state.push).not.toHaveBeenCalledWith(expect.stringMatching(/^\/conversations\/\d+$/))
  })

  it('deletes the selected conversation after confirmation and returns to the start page', async () => {
    state.deleteConversation.mockResolvedValue(null)
    state.listConversations.mockResolvedValueOnce([{ id: 9, title: '函数追问', assistantName: '数学助手' }])
      .mockResolvedValueOnce([])
    const wrapper = mount(ConversationsView, { global })
    await flushPromises()

    await wrapper.find('[aria-label="删除会话 函数追问"]').trigger('click')
    expect(state.deleteConversation).not.toHaveBeenCalled()
    await wrapper.find('.modal-footer .button--danger').trigger('click')
    await flushPromises()

    expect(state.deleteConversation).toHaveBeenCalledWith(9)
    expect(state.push).toHaveBeenCalledWith('/conversations')
    expect(wrapper.text()).toContain('选择一个智能体开始')
  })
})
