const TOOL_DISPLAY_NAMES = {
  knowledge_search: '知识检索',
  formula_hint: '公式提示',
  calculator: '计算器',
}

export function toolDisplayName(nameOrRef) {
  const name = String(nameOrRef || '').split('@', 1)[0]
  return TOOL_DISPLAY_NAMES[name] || name || '未知工具'
}

export function toolReferenceLabel(ref) {
  const value = String(ref || '')
  const displayName = toolDisplayName(value)
  return displayName === value ? value : `${displayName} · ${value}`
}
