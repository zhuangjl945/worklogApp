package com.zjl.worklog.work;

import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.work.entity.WorkStatus;
import com.zjl.worklog.work.mapper.WorkStatusMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/work/statuses")
public class WorkStatusController {

    private final WorkStatusMapper statusMapper;

    public WorkStatusController(WorkStatusMapper statusMapper) {
        this.statusMapper = statusMapper;
    }

    @GetMapping("/enabled")
    public ApiResponse<List<WorkStatus>> enabled() {
        return ApiResponse.ok(statusMapper.selectAllEnabled());
    }
}
