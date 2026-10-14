import { describe, expect, it, vi } from 'vitest'
import { parseEventStream } from './sse.js'

function byteStream(bytes, cuts) {
  let offset = 0
  return new ReadableStream({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close()
        return
      }
      const size = cuts.shift() || bytes.length
      controller.enqueue(bytes.slice(offset, offset + size))
      offset += size
    },
  })
}

describe('parseEventStream', () => {
  it('parses CRLF, multiple frames and UTF-8 text split across byte chunks', async () => {
    const source = 'event: meta\r\ndata: {"citations":[]}\r\n\r\nevent: delta\r\ndata: {"text":"中文"}\r\n\r\nevent: done\r\ndata: {"messageId":7}\r\n\r\n'
    const bytes = new TextEncoder().encode(source)
    const events = []

    const result = await parseEventStream(byteStream(bytes, [1, 2, 17, 3, 5, 1, 4]), (event) => events.push(event))

    expect(result.doneSeen).toBe(true)
    expect(events).toEqual([
      { event: 'meta', data: { citations: [] } },
      { event: 'delta', data: { text: '中文' } },
      { event: 'done', data: { messageId: 7 } },
    ])
  })

  it('ignores heartbeats and unknown events but consumes a final unterminated frame', async () => {
    const events = []
    const source = ': heartbeat\n\nevent: extension\ndata: {"x":1}\n\nevent: delta\ndata: {"text":"末尾"}'

    const result = await parseEventStream(byteStream(new TextEncoder().encode(source), [8]), (event) => events.push(event))

    expect(result.doneSeen).toBe(false)
    expect(events).toEqual([{ event: 'delta', data: { text: '末尾' } }])
  })

  it('rejects malformed JSON without invoking the consumer', async () => {
    const consumer = vi.fn()
    const stream = byteStream(new TextEncoder().encode('event: delta\ndata: {bad}\n\n'), [100])

    await expect(parseEventStream(stream, consumer)).rejects.toThrow('无法解析')
    expect(consumer).not.toHaveBeenCalled()
  })
})
