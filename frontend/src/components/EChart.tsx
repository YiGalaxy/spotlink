import { useEffect, useRef } from 'react'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, ScatterChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

// 按需注册组件，避免引入完整 ECharts 包。
echarts.use([
  LineChart,
  BarChart,
  ScatterChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  MarkLineComponent,
  CanvasRenderer,
])

interface Props {
  option: EChartsCoreOption
  height?: number
  loading?: boolean
}

/** 复用 ECharts 实例，仅更新 option。 */
export default function EChart({ option, height = 320, loading }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    if (!containerRef.current) {
      return
    }
    const chart = echarts.init(containerRef.current)
    chartRef.current = chart

    // 容器尺寸变化时重排图表。
    const observer = new ResizeObserver(() => chart.resize())
    observer.observe(containerRef.current)

    return () => {
      observer.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [])

  useEffect(() => {
    if (chartRef.current) {
      // 完整替换 option，避免保留过期 series。
      chartRef.current.setOption(option, true)
    }
  }, [option])

  useEffect(() => {
    if (!chartRef.current) {
      return
    }
    if (loading) {
      chartRef.current.showLoading('default', { text: '加载中' })
    } else {
      chartRef.current.hideLoading()
    }
  }, [loading])

  return <div ref={containerRef} style={{ height, width: '100%' }} />
}
