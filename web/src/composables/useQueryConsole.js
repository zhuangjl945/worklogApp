import { onScopeDispose, ref, watch } from 'vue'

/**
 * 检索台的公共机械部分。
 *
 * 只抽三件事，条件摘要（chips）和常用视图的命中判断都留在各页面里 ——
 * 那些是「这一页的条件长什么样」的问题，硬抽成公共层只会变成一堆回调配置。
 *   1) expanded —— 「更多条件」的折叠状态
 *   2) 自动重查 —— 筛选字段一变就防抖发请求，所以不需要「查询」按钮
 *   3) run      —— 要立刻查的场合（回车、清空全部、切常用视图）手动调用
 *
 * @param getSignature () => string，只用筛选字段拼出的指纹；页码、每页条数别放进来，否则翻页会多打一次请求
 * @param runQuery     () => void，真正发请求的函数
 * @param debounce     防抖毫秒，默认 300
 */
export function useQueryConsole({ getSignature, runQuery, debounce = 300 }) {
  const expanded = ref(false)
  let timer = null

  function run() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    runQuery()
  }

  function schedule() {
    if (timer) clearTimeout(timer)
    // 连续敲字、连点胶囊时只发一次请求
    timer = setTimeout(run, debounce)
  }

  watch(getSignature, schedule)

  // 组件销毁时清掉待发的定时器，免得请求打在已卸载的实例上
  onScopeDispose(() => {
    if (timer) clearTimeout(timer)
  })

  return { expanded, run, schedule }
}