import { describe, expect, it } from 'vitest'
import { decimalUnits, positiveDecimalRule, formatDecimal } from './decimal'

describe('交易小数输入', () => {
  it('大数量与尾零保留确定的整数值', () => {
    expect(decimalUnits('999999999999999.999', 3)).toBe(999999999999999999n)
    expect(decimalUnits('1.0000', 3)).toBe(1000n)
    expect(decimalUnits('0.0015', 3)).toBeNull()
    expect(decimalUnits('1e3', 3)).toBeNull()
    expect(formatDecimal('999999999999999.9999')).toBe('999,999,999,999,999.9999')
  })
  it('拒绝零、溢出和超精度单价', async () => {
    const { validator } = positiveDecimalRule(4, '999999999999999.9999', '单价')
    await expect(validator(null, '999999999999999.9999')).resolves.toBeUndefined()
    for (const value of ['0', '-1', '1000000000000000', '1.00001']) {
      await expect(validator(null, value)).rejects.toThrow()
    }
  })
})
