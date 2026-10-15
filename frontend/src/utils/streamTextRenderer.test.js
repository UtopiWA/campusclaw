import { describe, expect, it, vi } from 'vitest'
import { createStreamTextRenderer } from './streamTextRenderer.js'

describe('createStreamTextRenderer', () => {
  it('renders a burst one Unicode code point per scheduled frame and drains last', async () => {
    const frames = []
    const append = vi.fn()
    const renderer = createStreamTextRenderer(append, (callback) => frames.push(callback))

    renderer.enqueue('甲😀乙')
    let drained = false
    const completion = renderer.drain().then(() => { drained = true })

    expect(append).not.toHaveBeenCalled()
    expect(frames).toHaveLength(1)
    frames.shift()()
    expect(append).toHaveBeenLastCalledWith('甲')
    expect(drained).toBe(false)
    frames.shift()()
    expect(append).toHaveBeenLastCalledWith('😀')
    frames.shift()()
    await completion

    expect(append.mock.calls.map(([value]) => value)).toEqual(['甲', '😀', '乙'])
    expect(drained).toBe(true)
  })

  it('accepts later deltas while an earlier burst is still rendering', async () => {
    const frames = []
    const output = []
    const renderer = createStreamTextRenderer((value) => output.push(value), (callback) => frames.push(callback))

    renderer.enqueue('前后')
    frames.shift()()
    renderer.enqueue('续')
    while (frames.length) frames.shift()()
    await renderer.drain()

    expect(output.join('')).toBe('前后续')
  })
})
