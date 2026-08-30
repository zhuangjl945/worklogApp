const fs = require('fs');
const path = require('path');

const base = 'D:/Cursors/worklog/web/src/views';

const newStyles = {};

newStyles['UsersView.vue'] = 
/* 页面容器 */
.page {
  padding: 20px;
}

/* 顶部工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3, 12px);
  margin-bottom: 16px;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

.titleIcon {
  font-size: 22px;
  color: var(--color-primary, #3b82f6);
}

/* 页面标题：18px / 700 / -0.3px */
.title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--color-slate-900, #1e293b);
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

/* 卡片容器 */
.card {
  border-radius: var(--radius-lg, 14px);
  border-color: var(--color-slate-300, #e2e8f0);
}

.filter-bar {
  margin-bottom: 16px;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2, 8px);
}

.filter-form .action-buttons {
  margin-top: auto;
  padding-bottom: 2px;
}

/* 表格头部样式统一 */
:deep(.el-table th.el-table__cell) {
  background-color: var(--color-slate-200, #f1f5f9);
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  color: var(--color-slate-700, #475569);
}

.pagination {
  margin-top: 16px;
}
;

newStyles['DeptView.vue'] = 
/* 页面容器 */
.page {
  padding: 20px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* 顶部工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3, 12px);
  margin-bottom: 16px;
  flex: 0 0 auto;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

.titleIcon {
  font-size: 22px;
  color: var(--color-primary, #3b82f6);
}

/* 页面标题：18px / 700 / -0.3px */
.title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--color-slate-900, #1e293b);
}

/* 布局网格 */
.layout-grid {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
}

.tree-card, .detail-card {
  border-radius: var(--radius-lg, 14px);
  border-color: var(--color-slate-300, #e2e8f0);
  display: flex;
  flex-direction: column;
}

.tree-toolbar {
  display: flex;
  gap: var(--space-2, 8px);
  margin-bottom: var(--space-3, 12px);
}

.dept-tree {
  flex: 1 1 auto;
  overflow: auto;
  max-height: calc(100vh - 220px);
}

:deep(.el-tree-node__content) {
  height: auto;
  padding: 6px 0;
}

:deep(.el-tree-node.is-current > .el-tree-node__content) {
  background-color: var(--el-color-primary-light-9);
}

.node {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2, 8px);
}

.node-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--color-slate-700, #475569);
}

.node-tag {
  flex: 0 0 auto;
}

/* 详情区域 */
.detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3, 12px);
  margin-bottom: 16px;
}

.detail-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--color-slate-900, #1e293b);
}

.detail-actions {
  display: flex;
  gap: var(--space-2, 8px);
}

.detail-body {
  flex: 1;
}

@media (max-width: 900px) {
  .layout-grid {
    grid-template-columns: 1fr;
  }
}
;

newStyles['SupplierListView.vue'] = 
/* 页面容器 */
.page {
  padding: 20px;
}

/* 顶部工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3, 12px);
  margin-bottom: 16px;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

.titleIcon {
  font-size: 22px;
  color: var(--color-primary, #3b82f6);
}

/* 页面标题：18px / 700 / -0.3px */
.title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--color-slate-900, #1e293b);
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

/* 卡片容器 */
.card {
  border-radius: var(--radius-lg, 14px);
  border-color: var(--color-slate-300, #e2e8f0);
}

.filter-bar {
  margin-bottom: 16px;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2, 8px);
}

/* 表格头部样式统一 */
:deep(.el-table th.el-table__cell) {
  background-color: var(--color-slate-200, #f1f5f9);
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  color: var(--color-slate-700, #475569);
}

.pagination {
  margin-top: 16px;
}

/* 禁用行样式 */
:deep(.row-disabled) {
  color: var(--color-slate-500, #94a3b8);
}
;

newStyles['WorkCategoryView.vue'] = 
/* 页面容器 */
.page {
  padding: 20px;
}

/* 顶部工具栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3, 12px);
  margin-bottom: 16px;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

.titleIcon {
  font-size: 22px;
  color: var(--color-primary, #3b82f6);
}

/* 页面标题：18px / 700 / -0.3px */
.title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--color-slate-900, #1e293b);
}

/* 卡片容器 */
.card {
  border-radius: var(--radius-lg, 14px);
  border-color: var(--color-slate-300, #e2e8f0);
}

.filter-bar {
  margin-bottom: 16px;
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2, 8px);
}

.filter-form .action-buttons {
  margin-top: auto;
  padding-bottom: 2px;
}

/* 表格头部样式统一 */
:deep(.el-table th.el-table__cell) {
  background-color: var(--color-slate-200, #f1f5f9);
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  color: var(--color-slate-700, #475569);
}

.pagination {
  margin-top: 16px;
}
;

newStyles['WorkloadCategoryReportView.vue'] = 
/* 页面容器 */
.page {
  padding: 20px;
}

/* 顶部工具栏 */
.toolbar {
  margin-bottom: 16px;
}

.titleWrap {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
}

.titleIcon {
  font-size: 22px;
  color: var(--color-primary, #3b82f6);
}

/* 页面标题：18px / 700 / -0.3px */
.title {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.3px;
  color: var(--color-slate-900, #1e293b);
}

.subtitle {
  margin-top: var(--space-1, 4px);
  font-size: 12px;
  color: var(--color-slate-600, #64748b);
}

/* 卡片容器 */
.card {
  border-radius: var(--radius-lg, 14px);
  border-color: var(--color-slate-300, #e2e8f0);
}

/* 筛选区域 */
.filters {
  margin-bottom: var(--space-3, 12px);
  padding: var(--space-3, 12px);
  border-radius: var(--radius-md, 10px);
  border: 1px solid var(--color-slate-300, #e2e8f0);
  background: var(--color-slate-100, #f8fafc);
}

.filter-form {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2, 8px) var(--space-3, 12px);
}

.filter-form .action-buttons {
  margin-top: auto;
  padding-bottom: 2px;
}

/* 表格头部样式统一 */
:deep(.el-table th.el-table__cell) {
  background-color: var(--color-slate-200, #f1f5f9);
  font-weight: 600;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  color: var(--color-slate-700, #475569);
}

/* 错误与空状态 */
.error-text {
  text-align: center;
  color: #b42318;
  padding: 18px;
  border: 1px dashed #fda29b;
  border-radius: var(--radius-md, 10px);
  background: #fffbfa;
  margin-bottom: var(--space-3, 12px);
}

.empty-text {
  text-align: center;
  color: var(--color-slate-600, #64748b);
  padding: 22px;
  border: 1px dashed var(--color-slate-300, #e2e8f0);
  border-radius: var(--radius-md, 10px);
  background: var(--color-slate-100, #f8fafc);
  margin-bottom: var(--space-3, 12px);
}
;

const fileNames = Object.keys(newStyles);
for (const name of fileNames) {
  const filePath = path.join(base, name);
  let content = fs.readFileSync(filePath, 'utf-8');
  const re = /(<style scoped>)([\s\S]*?)(<\/style>)/;
  const newContent = content.replace(re, '\' + newStyles[name] + '\');
  if (newContent === content) {
    console.log('SKIP (no match): ' + name);
  } else {
    fs.writeFileSync(filePath, newContent, 'utf-8');
    console.log('OK: ' + name);
  }
}
