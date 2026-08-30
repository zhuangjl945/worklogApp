package com.zjl.worklog.work.dto;

import lombok.Data;

@Data
public class CategoryTemplate {
    private String titlePattern;
    private String contentTemplate;
    private Boolean requireContent;
    private Boolean requireEndTime;
    private Boolean requireImage;

    public boolean requireContent() {
        return Boolean.TRUE.equals(requireContent);
    }

    public boolean requireEndTime() {
        return Boolean.TRUE.equals(requireEndTime);
    }

    public boolean requireImage() {
        return Boolean.TRUE.equals(requireImage);
    }
}
