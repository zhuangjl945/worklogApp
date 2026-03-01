package com.zjl.worklog.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private long page;
    private long size;
    private long total;
    private List<T> records;

    public static <T> PageResponse<T> of(long page, long size, long total, List<T> records) {
        return new PageResponse<>(page, size, total, records);
    }
}
