import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { apiRequest, authState, routeState } = vi.hoisted(() => ({
  apiRequest: vi.fn(),
  authState: { user: null, loaded: true },
  routeState: { query: {} },
}))

vi.mock('../services/api.js', () => ({ apiRequest }))
vi.mock('../services/auth.js', () => ({ authState, logout: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: vi.fn() }),
  useRoute: () => routeState,
}))

import MaterialsView from './MaterialsView.vue'

// jsdom 不允许直接写入文件输入框的 value，因此按浏览器事件形态注入只读 files 列表。
async function selectFile(wrapper, file) {
  const input = wrapper.find('.file-drop input')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [file] })
  await input.trigger('change')
}

describe('MaterialsView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
    apiRequest.mockResolvedValue([])
    routeState.query = {}
  })

  it('shows management controls to a teacher', async () => {
    authState.user = { username: 'teacher-a', role: 'teacher', className: '班级 A' }
    apiRequest.mockResolvedValueOnce([{
      id: 7, title: '函数讲义', originalFilename: 'function.md', hasFile: true,
      indexStatus: 'READY', indexStrategy: 'AUTO',
      fileSizeBytes: 128, createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
    }])
    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('上传讲义')
    expect(wrapper.text()).toContain('教师')
    expect(wrapper.find('[aria-label="重命名 函数讲义"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="重建索引 函数讲义"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="查看 函数讲义"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).toContain('可检索')

    await wrapper.find('[aria-label="重建索引 函数讲义"]').trigger('click')
    await wrapper.find('.index-form select').setValue('CUSTOM')
    const numberInputs = wrapper.findAll('.index-form input[type="number"]')
    await numberInputs[0].setValue('600')
    await numberInputs[1].setValue('20')
    await wrapper.find('.index-form input[type="checkbox"]').setValue(true)
    await wrapper.find('.modal-footer .button--primary').trigger('click')
    await flushPromises()
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/materials/7/index/rebuild', {
      method: 'POST',
      body: JSON.stringify({
        strategy: 'CUSTOM', maxCodePoints: 600, overlapPercent: 20,
        breakPreference: 'PARAGRAPH', preprocess: true,
      }),
    })
  })

  it('shows upload errors inside the open modal and clears them after choosing another file', async () => {
    authState.user = { username: 'teacher-a', role: 'teacher', className: '班级 A' }
    apiRequest
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new Error('Only .txt and .md files are supported'))

    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.find('.page-heading .button--primary').trigger('click')
    await selectFile(wrapper, new File(['bad'], 'bad.pdf', { type: 'application/pdf' }))
    await wrapper.find('.modal-footer .button--primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.find('.upload-error[role="alert"]').text()).toBe('Only .txt and .md files are supported')
    expect(wrapper.find('.file-drop').text()).toContain('bad.pdf')

    await selectFile(wrapper, new File(['valid'], 'valid.md', { type: 'text/markdown' }))
    expect(wrapper.find('.upload-error').exists()).toBe(false)
    expect(wrapper.find('.file-drop').text()).toContain('valid.md')
  })

  it('does not retain an upload error after closing and reopening the modal', async () => {
    authState.user = { username: 'teacher-a', role: 'teacher', className: '班级 A' }
    apiRequest
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new Error('File must not be empty'))

    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    await wrapper.find('.page-heading .button--primary').trigger('click')
    await selectFile(wrapper, new File(['bad'], 'bad.txt', { type: 'text/plain' }))
    await wrapper.find('.modal-footer .button--primary').trigger('click')
    await flushPromises()

    expect(wrapper.find('.upload-error').exists()).toBe(true)
    await wrapper.find('[aria-label="关闭弹窗"]').trigger('click')
    await wrapper.find('.page-heading .button--primary').trigger('click')

    expect(wrapper.find('.upload-error').exists()).toBe(false)
    expect(wrapper.find('.file-drop').text()).toContain('点击选择，或将文件拖到这里')
  })

  it('keeps a student view read-only', async () => {
    authState.user = { username: 'student-a1', role: 'student', className: '班级 A' }
    apiRequest.mockResolvedValueOnce([{
      id: 8, title: '示例知识', originalFilename: null, hasFile: false,
      indexStatus: 'PENDING', indexStrategy: null,
      fileSizeBytes: null, createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
    }])
    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('上传讲义')
    expect(wrapper.text()).toContain('学生')
    expect(wrapper.find('[aria-label="重命名 示例知识"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="重建索引 示例知识"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="查看 示例知识"]').attributes('disabled')).toBeDefined()
  })

  it('loads file content into a plain-text preview', async () => {
    authState.user = { username: 'student-a1', role: 'student', className: '班级 A' }
    apiRequest
      .mockResolvedValueOnce([{
        id: 9, title: '阅读方法', originalFilename: 'reading.txt', hasFile: true,
        fileSizeBytes: 12, createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
      }])
      .mockResolvedValueOnce('<script>不会执行</script>')
    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()

    await wrapper.find('[aria-label="查看 阅读方法"]').trigger('click')
    await flushPromises()

    expect(apiRequest).toHaveBeenLastCalledWith('/api/materials/9/content', { responseType: 'text' })
    expect(wrapper.find('pre.file-preview').text()).toBe('<script>不会执行</script>')
  })

  it('opens a class-scoped citation context from route parameters', async () => {
    authState.user = { username: 'student-a1', role: 'student', className: '班级 A' }
    routeState.query = { material: '9', chunk: '22' }
    apiRequest
      .mockResolvedValueOnce([{
        id: 9, title: '阅读方法', originalFilename: 'reading.txt', hasFile: true,
        indexStatus: 'READY', fileSizeBytes: 12,
        createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
      }])
      .mockResolvedValueOnce({
        materialId: 9, chunkId: 22, startOffset: 3, endOffset: 10,
        neighbors: [{ chunkId: 22, chunkIndex: 0, excerpt: '被引用的正文', target: true }],
      })
      .mockResolvedValueOnce('完整原文件')

    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()

    expect(apiRequest).toHaveBeenCalledWith('/api/materials/9/chunks/22/context')
    expect(apiRequest).toHaveBeenCalledWith('/api/materials/9/content', { responseType: 'text' })
    expect(wrapper.find('.citation-context-target').text()).toContain('被引用的正文')
    expect(wrapper.find('pre.file-preview').text()).toBe('完整原文件')
  })

  it('lets a teacher retry a retryable failed material', async () => {
    authState.user = { username: 'teacher-a', role: 'teacher', className: '班级 A' }
    apiRequest
      .mockResolvedValueOnce([{
        id: 10, title: '失败讲义', originalFilename: 'failed.md', hasFile: true,
        indexStatus: 'FAILED', processingStage: 'INDEXING', failureCode: 'DEPENDENCY_UNAVAILABLE',
        retryable: true, createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
      }])
      .mockResolvedValueOnce({ indexStatus: 'PENDING' })
      .mockResolvedValueOnce([])

    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('失败阶段：索引构建')
    expect(wrapper.text()).toContain('模型或向量服务暂时不可用，可以重新处理。')
    await wrapper.find('[aria-label="重新处理 失败讲义"]').trigger('click')
    await flushPromises()

    expect(apiRequest).toHaveBeenCalledWith('/api/materials/10/processing/retry', { method: 'POST' })
    expect(wrapper.text()).toContain('材料已重新排队处理')
  })

  it.each([
    ['PARSING', '正文解析'],
    ['QUEUED', '任务调度'],
    ['UNKNOWN_STAGE', '后台处理'],
    [undefined, '后台处理'],
  ])('shows a safe label for failed processing stage %s', async (processingStage, expectedLabel) => {
    authState.user = { username: 'student-a1', role: 'student', className: '班级 A' }
    apiRequest.mockResolvedValueOnce([{
      id: 11, title: '待检查讲义', originalFilename: 'check.txt', hasFile: true,
      indexStatus: 'FAILED', processingStage, failureCode: 'PROCESSING_ERROR',
      retryable: true, createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
    }])

    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()

    expect(wrapper.text()).toContain(`失败阶段：${expectedLabel}`)
    expect(wrapper.text()).toContain('后台处理失败，可以稍后重新处理。')
    expect(wrapper.text()).not.toContain('UNKNOWN_STAGE')
    expect(wrapper.find('[aria-label="重新处理 待检查讲义"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="删除 待检查讲义"]').exists()).toBe(false)
  })
})
