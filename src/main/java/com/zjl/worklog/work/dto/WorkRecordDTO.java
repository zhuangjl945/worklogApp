package com.zjl.worklog.work.dto;

import com.zjl.worklog.work.entity.WorkRecord;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class WorkRecordDTO {
    private Long id;
    private Long userId;
    private Long deptId;
    private Long bizDeptId;
    private Long categoryId;
    private Integer statusId;
    private String title;
    private String content;
    private String imageUrls;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMinutes;
    private Integer isImportant;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public WorkRecordDTO(WorkRecord entity) {
        this.id = entity.getId();
        this.userId = entity.getUserId();
        this.deptId = entity.getDeptId();
        this.bizDeptId = entity.getBizDeptId();
        this.categoryId = entity.getCategoryId();
        this.statusId = entity.getStatusId();
        this.title = entity.getTitle();
        this.content = entity.getContent();
        this.imageUrls = entity.getImageUrls();
        this.startTime = entity.getStartTime();
        this.endTime = entity.getEndTime();
        this.durationMinutes = entity.getDurationMinutes();
        this.isImportant = entity.getIsImportant();
        this.createTime = entity.getCreateTime();
        this.updateTime = entity.getUpdateTime();
    }
}
