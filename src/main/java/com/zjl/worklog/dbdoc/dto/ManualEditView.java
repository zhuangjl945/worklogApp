package com.zjl.worklog.dbdoc.dto;

import com.zjl.worklog.dbdoc.entity.DbTableDocEntity;
import lombok.Builder;
import lombok.Data;

/**
 * 人工修订表结构的返回。
 *
 * 为什么要把「改没改」说出来：页面上点了保存，用户看到的应该是准话 ——
 * 结构真变了才会有新的历史版本，一个字都没动就只是「和保存前一样」，不该假装存了一条记录。
 */
@Data
@Builder
public class ManualEditView {

    /** 这次提交是否真的改变了当前结构（含更名） */
    private boolean changed;
    /** 变更摘要，与 db_table_version.diff_summary 同一个口径，没变时为空 */
    private String diffSummary;
    /** 保存之后的目录行，前端直接拿它刷新展示 */
    private DbTableDocEntity table;
}