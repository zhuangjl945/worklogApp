<script setup>
// ============================================================
// 检索台 QueryConsole
// ------------------------------------------------------------
// 把「查询类控件」从数据卡片里独立出来，单独成区，固定三段：
//   1) 预设轨道 —— 常用视图，一键切范围
//   2) 检索输入 —— 主搜索框 + 高频条件 + 更多条件折叠
//   3) 检索式   —— 用等宽字把「此刻真正生效的条件」回显成一行可点删的表达式
// 本组件只负责版式与条件回显，具体筛选项由使用方通过插槽塞进来，
// 所以其它列表页可以直接复用同一副骨架。
// ============================================================

const props = defineProps({
  // 常用视图：[{ key, label }]
  presets: { type: Array, default: () => [] },
  // 当前命中的视图 key；null 表示用户在自定义筛选
  activePreset: { type: String, default: null },
  // 正在生效的条件：[{ key, field, value }]
  chips: { type: Array, default: () => [] },
  // 结果计数文案，例如「共 2 条」
  resultText: { type: String, default: '' },
  // 是否正在查询中
  busy: { type: Boolean, default: false },
  // 更多条件是否展开（v-model:expanded）
  expanded: { type: Boolean, default: false },
  // 折叠区内正在生效的条件数，用于给「更多条件」挂角标
  hiddenActiveCount: { type: Number, default: 0 }
})

const emit = defineEmits(['update:expanded', 'select-preset', 'remove-chip', 'clear-all'])
</script>

<template>
  <section class="qc" :class="{ 'is-busy': props.busy }" aria-label="检索台">
    <!-- 1) 预设轨道 -->
    <div v-if="props.presets.length" class="qc-rail">
      <span class="qc-eyebrow">常用视图</span>
      <div class="qc-presets" role="group" aria-label="常用视图">
        <button
          v-for="p in props.presets"
          :key="p.key"
          type="button"
          class="qc-preset"
          :class="{ 'is-on': p.key === props.activePreset }"
          :aria-pressed="p.key === props.activePreset"
          @click="emit('select-preset', p.key)"
        >
          {{ p.label }}
        </button>
      </div>
      <div class="qc-rail-tail">
        <slot name="rail-tail" />
      </div>
    </div>

    <!-- 2) 检索输入 -->
    <div class="qc-body">
      <div class="qc-lead">
        <slot name="search" />
      </div>
      <div class="qc-inline">
        <slot name="inline" />
      </div>
      <button
        type="button"
        class="qc-toggle"
        :class="{ 'is-on': props.expanded }"
        :aria-expanded="props.expanded"
        @click="emit('update:expanded', !props.expanded)"
      >
        <span>更多条件</span>
        <span v-if="props.hiddenActiveCount" class="qc-badge">{{ props.hiddenActiveCount }}</span>
        <svg class="qc-caret" viewBox="0 0 12 12" aria-hidden="true">
          <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
    </div>

    <!-- 折叠的条件网格：用 0fr/1fr 做高度过渡，不需要写死 max-height -->
    <Transition name="qc-expand">
      <div v-show="props.expanded" class="qc-more-wrap">
        <div class="qc-more">
          <slot name="more" />
        </div>
      </div>
    </Transition>

    <!-- 3) 检索式回显 -->
    <div class="qc-expr">
      <span class="qc-expr-label">
        <span class="qc-dot" aria-hidden="true" />
        {{ props.busy ? '查询中' : '筛选' }}
      </span>
      <div class="qc-chips">
        <span v-if="!props.chips.length" class="qc-chips-empty">无条件 · 显示全部记录</span>
        <button
          v-for="c in props.chips"
          :key="c.key"
          type="button"
          class="qc-chip"
          :title="`点击移除：${c.field} ${c.value}`"
          @click="emit('remove-chip', c.key)"
        >
          <i class="qc-chip-field">{{ c.field }}</i>
          <b class="qc-chip-value">{{ c.value }}</b>
          <span class="qc-chip-x" aria-hidden="true">×</span>
        </button>
      </div>
      <div class="qc-expr-tail">
        <span v-if="props.resultText" class="qc-count">{{ props.resultText }}</span>
        <button v-if="props.chips.length" type="button" class="qc-clear" @click="emit('clear-all')">清空全部</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.qc {
  background: var(--g-bg);
  border: 1px solid var(--g-border);
  border-radius: var(--g-radius-lg);
  overflow: hidden;
}

/* --- 1 预设轨道 --- */
.qc-rail {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 40px;
  padding: 0 14px;
  background: var(--g-bg-subtle);
  border-bottom: 1px solid var(--g-border);
}

.qc-eyebrow {
  flex: none;
  font-family: var(--g-font-mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--g-text-faint);
}

.qc-presets {
  display: flex;
  align-items: center;
  gap: 2px;
  overflow-x: auto;
  scrollbar-width: none;
}
.qc-presets::-webkit-scrollbar { display: none; }

.qc-preset {
  flex: none;
  appearance: none;
  border: 0;
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  color: var(--g-text-secondary);
  padding: 5px 11px;
  border-radius: 999px;
  transition: background-color 0.12s ease, color 0.12s ease;
}
.qc-preset:hover {
  background: var(--g-bg-muted);
  color: var(--g-text);
}
.qc-preset.is-on {
  background: var(--g-accent);
  color: #fff;
  font-weight: 600;
}

.qc-rail-tail {
  margin-left: auto;
  flex: none;
}

/* --- 2 检索输入 --- */
.qc-body {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 14px;
  padding: 12px 14px;
}

.qc-lead {
  flex: 1 1 260px;
  min-width: 200px;
}

.qc-inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.qc-toggle {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  appearance: none;
  border: 1px solid var(--g-border-strong);
  background: var(--g-bg);
  border-radius: var(--g-radius);
  cursor: pointer;
  font: inherit;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--g-text-secondary);
  padding: 6px 10px;
  transition: border-color 0.12s ease, color 0.12s ease, background-color 0.12s ease;
}
.qc-toggle:hover {
  border-color: var(--g-text-faint);
  color: var(--g-text);
}
.qc-toggle.is-on {
  background: var(--g-bg-muted);
  border-color: var(--g-text-faint);
  color: var(--g-text);
}

.qc-caret {
  width: 12px;
  height: 12px;
  transition: transform 0.2s ease;
}
.qc-toggle.is-on .qc-caret { transform: rotate(180deg); }

.qc-badge {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--g-accent);
  color: #fff;
  font-family: var(--g-font-mono);
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
}

/* 折叠区：0fr -> 1fr 的高度过渡 */
.qc-more-wrap {
  display: grid;
  grid-template-rows: 1fr;
}
.qc-more {
  min-height: 0;
  overflow: hidden;
  padding: 2px 14px 14px;
  border-top: 1px solid var(--g-border);
}

.qc-expand-enter-active,
.qc-expand-leave-active {
  transition: grid-template-rows 0.2s ease, opacity 0.16s ease;
}
.qc-expand-enter-from,
.qc-expand-leave-to {
  grid-template-rows: 0fr;
  opacity: 0;
}

/* --- 3 检索式回显 --- */
.qc-expr {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 38px;
  padding: 7px 14px;
  background: var(--g-bg-subtle);
  border-top: 1px solid var(--g-border);
}

.qc-expr-label {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--g-font-mono);
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--g-text-faint);
}

.qc-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--g-border-strong);
}
.qc.is-busy .qc-dot {
  background: var(--g-success);
  animation: qc-pulse 0.9s ease-in-out infinite;
}
@keyframes qc-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
}

.qc-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.qc-chips-empty {
  font-family: var(--g-font-mono);
  font-size: 11.5px;
  color: var(--g-text-faint);
}

.qc-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  height: 24px;
  padding: 0 7px;
  border: 0;
  border-radius: var(--g-radius-sm);
  background: var(--g-bg);
  box-shadow: inset 0 0 0 1px var(--g-border-strong);
  cursor: pointer;
  font-family: var(--g-font-mono);
  font-size: 11.5px;
  transition: box-shadow 0.12s ease;
}
.qc-chip:hover { box-shadow: inset 0 0 0 1px var(--g-text); }
.qc-chip:hover .qc-chip-x { color: var(--g-danger); }

.qc-chip-field {
  font-style: normal;
  color: var(--g-text-faint);
}
.qc-chip-value {
  font-weight: 600;
  color: var(--g-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.qc-chip-x {
  color: var(--g-text-faint);
  font-size: 13px;
  line-height: 1;
}

.qc-expr-tail {
  margin-left: auto;
  flex: none;
  display: flex;
  align-items: center;
  gap: 12px;
}

.qc-count {
  font-family: var(--g-font-mono);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  color: var(--g-text-secondary);
}

.qc-clear {
  appearance: none;
  border: 0;
  background: transparent;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  color: var(--g-text-muted);
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-color: var(--g-border-strong);
  transition: color 0.12s ease, text-decoration-color 0.12s ease;
}
.qc-clear:hover {
  color: var(--g-danger);
  text-decoration-color: currentColor;
}

/* --- 无障碍：键盘焦点要看得见 --- */
.qc :deep(button:focus-visible),
.qc :deep(.el-input__inner:focus-visible) {
  outline: 2px solid var(--g-text);
  outline-offset: 2px;
}

@media (max-width: 720px) {
  .qc-rail { height: auto; flex-wrap: wrap; padding: 8px 12px; }
  .qc-rail-tail { display: none; }
  .qc-body { padding: 10px 12px; }
  .qc-toggle { margin-left: 0; }
  .qc-expr { flex-wrap: wrap; }
  .qc-expr-tail { margin-left: 0; width: 100%; justify-content: space-between; }
}

@media (prefers-reduced-motion: reduce) {
  .qc * { transition: none !important; animation: none !important; }
}
</style>