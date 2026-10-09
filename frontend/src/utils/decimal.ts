/** 表单的定点小数按字符串传输、用整数比较，避免超过 JS 精度后改变报价或数量。 */
export function decimalUnits(value: unknown, scale: number): bigint | null {
  const text = String(value ?? '')
  if (!/^\d+(\.\d+)?$/.test(text)) return null
  const [whole, raw = ''] = text.split('.')
  const fraction = raw.replace(/0+$/, '')
  if (fraction.length > scale) return null
  return BigInt(whole) * (10n ** BigInt(scale)) + BigInt(fraction.padEnd(scale, '0') || '0')
}

export function formatDecimal(value: number | string): string {
  if (typeof value === 'number') return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value)
  if (!/^\d+(\.\d+)?$/.test(value)) return value
  const [whole, raw = ''] = value.split('.')
  const fraction = raw.replace(/0+$/, '')
  return BigInt(whole).toLocaleString('zh-CN') + (fraction ? `.${fraction}` : '')
}

export function positiveDecimalRule(scale: number, maximum: string, label: string) {
  return {
    validator: async (_: unknown, value: unknown) => {
      if (value === undefined || value === null || value === '') return
      const units = decimalUnits(value, scale)
      const max = decimalUnits(maximum, scale)!
      if (units === null || units <= 0n || units > max) {
        throw new Error(`${label}须大于 0，最多 15 位整数和 ${scale} 位小数`)
      }
    },
  }
}
