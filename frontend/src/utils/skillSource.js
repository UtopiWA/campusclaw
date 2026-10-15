const FIELD_NAMES = new Set(['名称', '适用条件', '资料', '工具', '启用'])

/** 将结构化表单稳定地组装为后端唯一认可的中文技能文本。 */
export function buildSkillSource(skill) {
  const name = skill.name?.trim()
  const triggerText = skill.triggerText?.trim()
  const instructions = skill.instructions?.trim()
  if (!name) throw new Error('请输入技能名称。')
  if (!triggerText) throw new Error('请输入至少一个触发条件。')
  if (!instructions) throw new Error('请输入行为说明。')
  if (!skill.knowledgeSearch) throw new Error('当前技能必须绑定知识检索工具。')

  return [
    `名称：${name}`,
    `适用条件：${triggerText}`,
    `资料：${skill.materialScope?.trim() || ''}`,
    '工具：knowledge_search',
    `启用：${skill.enabled ? '是' : '否'}`,
    '',
    instructions,
  ].join('\n')
}

/** 解析本地导入文本，仅用于回填表单；保存时仍以服务端解析和校验为准。 */
export function parseSkillSource(sourceText) {
  const normalized = String(sourceText || '').replace(/\r\n?/g, '\n')
  const separator = /\n[\t ]*\n/.exec(normalized)
  if (!separator) throw new Error('技能元数据与行为说明之间必须包含一个空行。')

  const values = new Map()
  for (const rawLine of normalized.slice(0, separator.index).split('\n')) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const chinese = line.indexOf('：')
    const ascii = line.indexOf(':')
    const indexes = [chinese, ascii].filter((value) => value >= 0)
    const fieldSeparator = indexes.length ? Math.min(...indexes) : -1
    if (fieldSeparator < 1) throw new Error('技能元数据必须使用“字段：值”格式。')
    const key = line.slice(0, fieldSeparator).trim()
    if (!FIELD_NAMES.has(key)) throw new Error(`未知技能字段：${key}`)
    if (values.has(key)) throw new Error(`技能字段不能重复：${key}`)
    values.set(key, line.slice(fieldSeparator + 1).trim())
  }

  const name = values.get('名称')?.trim()
  const triggerText = values.get('适用条件')?.trim()
  const instructions = normalized.slice(separator.index + separator[0].length).trim()
  const enabledValue = values.get('启用')?.trim().toLocaleLowerCase()
  if (!name || !triggerText || !instructions || !enabledValue) throw new Error('导入文本缺少名称、适用条件、启用或行为说明。')
  if (!['是', '否', 'true', 'false'].includes(enabledValue)) throw new Error('启用字段只能是“是/否”或 true/false。')
  const toolName = values.get('工具')?.trim() || 'knowledge_search'
  if (toolName !== 'knowledge_search') throw new Error('工具只允许 knowledge_search。')

  return {
    name,
    triggerText,
    materialScope: values.get('资料')?.trim() || '',
    instructions,
    enabled: ['是', 'true'].includes(enabledValue),
    knowledgeSearch: true,
  }
}
