package com.zjl.worklog.oss;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OssDeleteServiceTest {

    @Test
    @DisplayName("LIKE 通配符要转义，避免 key 里的 _ 把匹配范围放大")
    void escapeLikeKeepsUnderscoreLiteral() {
        assertEquals("work-records/2026/09/ab\\_c.jpg",
                OssDeleteService.escapeLike("work-records/2026/09/ab_c.jpg"));
        assertEquals("a\\\\b\\%c", OssDeleteService.escapeLike("a\\b%c"));
    }
}
