import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { authState } = vi.hoisted(() => ({
  authState: { user: null, loaded: true },
}))

vi.mock('../services/auth.js', () => ({ authState, logout: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn() }) }))

import AppShell from './AppShell.vue'

const global = {
  stubs: { RouterLink: { props: ['to'], template: '<a :data-to="to"><slot /></a>' } },
}

describe('AppShell assistant navigation', () => {
  beforeEach(() => {
    authState.user = { username: 'user', className: '班级 A', role: 'teacher' }
  })

  it('shows configuration only to teachers and conversation only to students', () => {
    const teacher = mount(AppShell, { props: { current: 'assistant' }, global })
    expect(teacher.find('[data-to="/assistants"]').exists()).toBe(true)
    expect(teacher.find('[data-to="/conversations"]').exists()).toBe(false)

    authState.user = { username: 'student', className: '班级 A', role: 'student' }
    const student = mount(AppShell, { props: { current: 'assistant' }, global })
    expect(student.find('[data-to="/assistants"]').exists()).toBe(false)
    expect(student.find('[data-to="/conversations"]').exists()).toBe(true)
  })
})
