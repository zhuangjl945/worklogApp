-- 工作分类：新建记录模板（JSON）
ALTER TABLE work_category
  ADD COLUMN template_json TEXT NULL COMMENT '新建记录模板JSON' AFTER description;
