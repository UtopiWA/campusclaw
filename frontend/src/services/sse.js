/**
 * 增量解析 fetch 返回的 SSE 字节流。保留末尾残片，支持 CRLF、跨块 UTF-8 与一块多帧。
 */
export async function parseEventStream(stream, onEvent) {
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let doneSeen = false

  const consumeFrames = (finished = false) => {
    buffer = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
    const frames = buffer.split('\n\n')
    buffer = finished ? '' : frames.pop()
    if (finished && frames.at(-1) === '') frames.pop()
    for (const frame of frames) {
      if (!frame.trim() || frame.trimStart().startsWith(':')) continue
      let event = 'message'
      const data = []
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
      }
      if (!data.length) continue
      let payload
      try {
        payload = JSON.parse(data.join('\n'))
      } catch {
        throw new Error('服务器返回了无法解析的流式数据。')
      }
      if (!['meta', 'delta', 'done', 'error'].includes(event)) continue
      onEvent({ event, data: payload })
      if (event === 'done') doneSeen = true
    }
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    consumeFrames()
  }
  buffer += decoder.decode()
  if (buffer.trim()) buffer += '\n\n'
  consumeFrames(true)
  return { doneSeen }
}
