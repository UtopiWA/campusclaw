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
      fileSizeBytes: 128, createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
    }])
    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).toContain('上传讲义')
    expect(wrapper.text()).toContain('教师')
    expect(wrapper.find('[aria-label="重命名 函数讲义"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="查看 函数讲义"]').attributes('disabled')).toBeUndefined()
  })

  it('keeps a student view read-only', async () => {
    authState.user = { username: 'student-a1', role: 'student', className: '班级 A' }
    apiRequest.mockResolvedValueOnce([{
      id: 8, title: '示例知识', originalFilename: null, hasFile: false,
      fileSizeBytes: null, createdAt: '2026-09-26T01:00:00Z', updatedAt: '2026-09-26T01:00:00Z',
    }])
    const wrapper = mount(MaterialsView, { global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('上传讲义')
    expect(wrapper.text()).toContain('学生')
    expect(wrapper.find('[aria-label="重命名 示例知识"]').exists()).toBe(false)
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
