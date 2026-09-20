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

// Registered individually rather than importing all of echarts. The full
// bundle is around a megabyte; these are the only pieces any chart here uses.
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

/**
 * A thin wrapper around ECharts.
 *
 * <p>The chart instance is created once and updated through `setOption`. It is
 * deliberately not recreated when the option changes: recreating would drop
 * zoom state and replay the entry animation on every data refresh, which on a
 * live feed means the chart never settles.
 */
export default function EChart({ option, height = 320, loading }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)

  useEffect(() => {
    if (!containerRef.current) {
      return
    }
    const chart = echarts.init(containerRef.current)
    chartRef.current = chart

    // Resize with the container, not with the data: a sidebar collapsing
    // changes the width without changing anything the data depends on.
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
      // `true` replaces the option rather than merging: a shorter series
      // merged into a longer one leaves stale points behind.
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
