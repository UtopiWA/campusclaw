/**
 * 把网络突发到达的正文拆成独立动画帧，避免一次响应块被 Vue 合并成整段显示。
 */
export function createStreamTextRenderer(append, schedule = scheduleAnimationFrame) {
  const pending = []
  const idleWaiters = []
  let scheduled = false

  function notifyIdle() {
    if (scheduled || pending.length) return
    idleWaiters.splice(0).forEach((resolve) => resolve())
  }

  function renderFrame() {
    scheduled = false
    const character = pending.shift()
    if (character !== undefined) append(character)
    if (pending.length) scheduleNext()
    else notifyIdle()
  }

  function scheduleNext() {
    if (scheduled || !pending.length) return
    scheduled = true
    schedule(renderFrame)
  }

  return {
    enqueue(text) {
      pending.push(...String(text || ''))
      scheduleNext()
    },
    drain() {
      if (!scheduled && !pending.length) return Promise.resolve()
      return new Promise((resolve) => idleWaiters.push(resolve))
    },
  }
}

/** 浏览器逐帧调度；非浏览器测试环境使用短定时器保持相同异步边界。 */
function scheduleAnimationFrame(callback) {
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    window.requestAnimationFrame(callback)
  } else {
    setTimeout(callback, 16)
  }
}
