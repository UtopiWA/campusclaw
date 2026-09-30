import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { apiRequest, authState } = vi.hoisted(() => ({
  apiRequest: vi.fn(),
  authState: { user: null, loaded: true },
}))

vi.mock('../services/api.js', () => ({ apiRequest }))
vi.mock('../services/auth.js', () => ({ authState, logout: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn() }) }))

import MaterialsView from './MaterialsView.vue'

describe('MaterialsView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
    apiRequest.mockResolvedValue([])
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
})
