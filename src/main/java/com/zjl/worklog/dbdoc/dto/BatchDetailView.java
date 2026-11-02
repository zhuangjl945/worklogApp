package com.zjl.worklog.dbdoc.dto;

import com.zjl.worklog.dbdoc.entity.DbDocEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 分析批次详情：批次本身 + 本次每张表的处理结论 */
@Data
public class BatchDetailView {

    /** 批次记录（含原始 SQL 与解析 JSON） */
    private DbDocEntity batch;
    /** 本次新增/变更/缺失的表清单 */
    private List<TableChangeItem> changes = new ArrayList<>();
    /** 是否已归类到系统（v1 遗留记录为 false，前端提示「重新归类」） */
    private boolean archived;
}