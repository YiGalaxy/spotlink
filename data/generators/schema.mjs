// 本项目只使用以下 JSON Schema 子集；不支持的关键字不能默默放行。
const annotations = new Set(['$schema', 'title', 'description']);
const supported = new Set(['type', 'const', 'enum', 'required', 'properties', 'additionalProperties', 'items', 'pattern', 'minLength', 'maxLength', 'minimum', 'maximum', 'minItems', 'maxItems']);
export function validateSchema(value, schema, at = '$') {
  for (const key of Object.keys(schema)) if (!supported.has(key) && !annotations.has(key)) throw new Error(`不支持的 schema 关键字 ${key}`);
  const fail = (reason) => { throw new Error(`${at}: ${reason}`); };
  if ('const' in schema && value !== schema.const) fail('常量不匹配');
  if (schema.enum && !schema.enum.includes(value)) fail('不在允许枚举中');
  if (schema.type) {
    const ok = schema.type === 'object' ? value !== null && typeof value === 'object' && !Array.isArray(value)
      : schema.type === 'array' ? Array.isArray(value)
      : schema.type === 'integer' ? Number.isSafeInteger(value)
      : typeof value === schema.type;
    if (!ok) fail(`应为 ${schema.type}`);
  }
  if (typeof value === 'string') {
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) fail('格式不匹配');
    if (schema.minLength != null && value.length < schema.minLength) fail('字符串太短');
    if (schema.maxLength != null && value.length > schema.maxLength) fail('字符串太长');
  }
  if (typeof value === 'number') {
    if (schema.minimum != null && value < schema.minimum) fail('数值低于下限');
    if (schema.maximum != null && value > schema.maximum) fail('数值超过上限');
  }
  if (Array.isArray(value)) {
    if (schema.minItems != null && value.length < schema.minItems) fail('数组太短');
    if (schema.maxItems != null && value.length > schema.maxItems) fail('数组太长');
    value.forEach((item, i) => schema.items && validateSchema(item, schema.items, `${at}[${i}]`));
  } else if (value !== null && typeof value === 'object') {
    for (const key of schema.required ?? []) if (!(key in value)) fail(`缺少 ${key}`);
    for (const [key, item] of Object.entries(value)) {
      if (schema.properties?.[key]) validateSchema(item, schema.properties[key], `${at}.${key}`);
      else if (schema.additionalProperties === false) fail(`未知字段 ${key}`);
      else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') validateSchema(item, schema.additionalProperties, `${at}.${key}`);
    }
  }
}
