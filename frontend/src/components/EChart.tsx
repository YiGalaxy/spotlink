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

// 逐个注册，而不是整包引入 echarts。完整包大约有一兆字节；而这里是本项目中
// 任何图表用到的全部组件。
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
 * ECharts 的一层薄封装。
 *
 * <p>图表实例只创建一次，之后通过 `setOption` 更新。option 变化时刻意不重建
 * 实例：重建会丢掉缩放状态，并在每次数据刷新时重放入场动画，而在实时数据流上
 * 这意味着图表永远静不下来。
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

    // 跟随容器变化调整尺寸，而不是跟随数据：侧边栏收起会改变宽度，
    // 却不改变数据所依赖的任何东西。
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
      // 传 `true` 是替换整个 option 而非合并：把较短的 series 合并进较长的
      // 那个，会留下过期的数据点。
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
