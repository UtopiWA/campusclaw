// @vitest-environment node
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

describe('frontend nginx routing', () => {
  it('disables buffering and exposes the SSE buffering control header', () => {
    const config = readFileSync(fileURLToPath(new URL('../nginx.conf', import.meta.url)), 'utf8')

    expect(config).toContain('proxy_buffering off;')
    expect(config).toContain('proxy_cache off;')
    expect(config).toContain('proxy_pass_header X-Accel-Buffering;')
  })

  it('proxies Springdoc resources before the Vue SPA fallback', () => {
    const config = readFileSync(fileURLToPath(new URL('../nginx.conf', import.meta.url)), 'utf8')
    const fallback = config.indexOf('location / {')

    expect(config.indexOf('location /swagger-ui/ {')).toBeGreaterThan(-1)
    expect(config.indexOf('location = /swagger-ui.html {')).toBeGreaterThan(-1)
    expect(config.indexOf('location = /v3/api-docs {')).toBeGreaterThan(-1)
    expect(config.indexOf('location /v3/api-docs/ {')).toBeGreaterThan(-1)
    expect(config.indexOf('location /swagger-ui/ {')).toBeLessThan(fallback)
    expect(config.indexOf('location = /v3/api-docs {')).toBeLessThan(fallback)
  })
})
