package com.zjl.worklog.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.UserContext;
import com.zjl.worklog.work.dto.CategoryTemplate;
import com.zjl.worklog.work.dto.WorkCategoryStat;
import com.zjl.worklog.work.dto.WorkCategorySummary;
import com.zjl.worklog.work.dto.WorkExpenseStat;
import com.zjl.worklog.work.dto.WorkRecordDTO;
import com.zjl.worklog.work.dto.WorkWeeklyItem;
import com.zjl.worklog.work.dto.WorkWeeklyReport;
import com.zjl.worklog.work.dto.WorkloadUserDeptCategoryStat;
import com.zjl.worklog.work.entity.WorkCategory;
import com.zjl.worklog.work.entity.WorkRecord;
import com.zjl.worklog.work.entity.WorkRecordExpense;
import com.zjl.worklog.work.entity.WorkRecordLog;
import com.zjl.worklog.work.mapper.WorkCategoryMapper;
import com.zjl.worklog.work.mapper.WorkRecordExpenseMapper;
import com.zjl.worklog.work.mapper.WorkRecordLogMapper;
import com.zjl.worklog.work.mapper.WorkRecordMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Validated
@RestController
@RequestMapping("/api/work/records")
public class WorkRecordController {

    private final WorkRecordMapper recordMapper;
    private final WorkRecordLogMapper logMapper;
    private final WorkRecordExpenseMapper expenseMapper;
    private final WorkCategoryMapper categoryMapper;
    private final ObjectMapper objectMapper;

    public WorkRecordController(WorkRecordMapper recordMapper, WorkRecordLogMapper logMapper, WorkRecordExpenseMapper expenseMapper,
                                WorkCategoryMapper categoryMapper, ObjectMapper objectMapper) {
        this.recordMapper = recordMapper;
        this.logMapper = logMapper;
        this.expenseMapper = expenseMapper;
        this.categoryMapper = categoryMapper;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkRecordDTO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) List<Long> categoryIds,
            @RequestParam(required = false) List<Integer> statusIds,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTimeTo,
            @RequestParam(required = false) Integer isImportant,
            @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime dueBefore
    ) {
        CurrentUser cu = requireLogin();
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        long total = recordMapper.count(cu.getId(), categoryId, categoryIds, statusIds, title, createTimeFrom, createTimeTo, isImportant, overdue, dueBefore);
        long offset = (page - 1) * size;
        List<WorkRecord> records = total == 0
                ? List.of()
                : recordMapper.selectPage(offset, size, cu.getId(), categoryId, categoryIds, statusIds, title, createTimeFrom, createTimeTo, isImportant, overdue, dueBefore);
        var views = records.stream().map(WorkRecordDTO::new).toList();
        return ApiResponse.ok(PageResponse.of(page, size, total, views));
    }

    @GetMapping("/stats/report/by-category")
    public ApiResponse<List<WorkCategoryStat>> statsByCategoryForReport(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTimeTo
    ) {
        // Since there is no admin role, this report is also for the current user.
        CurrentUser cu = requireLogin();
        return ApiResponse.ok(recordMapper.statsByCategory(cu.getId(), endTimeFrom, endTimeTo));
    }

    @GetMapping("/stats/dashboard/by-category")
    public ApiResponse<List<WorkCategorySummary>> statsByCategoryForDashboard(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTimeTo
    ) {
        CurrentUser cu = requireLogin();
        return ApiResponse.ok(recordMapper.getCategorySummary(cu.getId(), startTimeFrom, startTimeTo));
    }

    @GetMapping("/stats/by-expense-type")
    public ApiResponse<List<WorkExpenseStat>> statsByExpenseType(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime expenseTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime expenseTimeTo
    ) {
        CurrentUser cu = requireLogin();
        return ApiResponse.ok(expenseMapper.statsByExpenseType(cu.getId(), expenseTimeFrom, expenseTimeTo));
    }

    @GetMapping("/stats/report/user-dept-category")
    public ApiResponse<List<WorkloadUserDeptCategoryStat>> statsUserDeptCategory(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTimeTo,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) Long userId
    ) {
        requireLogin();
        return ApiResponse.ok(recordMapper.statsUserDeptCategory(endTimeFrom, endTimeTo, deptId, userId));
    }

    @GetMapping("/weekly-report")
    public ApiResponse<WorkWeeklyReport> weeklyReport(@RequestParam(defaultValue = "this") String week) {
        CurrentUser cu = requireLogin();
        boolean last = "last".equalsIgnoreCase(week);
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        if (last) {
            monday = monday.minusWeeks(1);
        }
        LocalDate sunday = monday.plusDays(6);
        LocalDateTime from = monday.atStartOfDay();
        LocalDateTime to = sunday.atTime(LocalTime.of(23, 59, 59));

        List<WorkWeeklyItem> done = recordMapper.selectWeeklyDone(cu.getId(), from, to);
        List<WorkWeeklyItem> doing = recordMapper.selectOpenWithCategory(cu.getId());
        LocalDateTime now = LocalDateTime.now();
        for (WorkWeeklyItem item : doing) {
            boolean overdue = item.getEndTime() != null
                    && item.getEndTime().isBefore(now)
                    && (item.getStatusId() != null && (item.getStatusId() == 1 || item.getStatusId() == 2));
            item.setOverdue(overdue);
        }
        List<WorkExpenseStat> expenses = expenseMapper.statsByExpenseType(cu.getId(), from, to);
        BigDecimal total = BigDecimal.ZERO;
        if (expenses != null) {
            for (WorkExpenseStat e : expenses) {
                if (e.getTotalAmount() != null) {
                    total = total.add(e.getTotalAmount());
                }
            }
        }

        WorkWeeklyReport report = new WorkWeeklyReport();
        report.setWeek(last ? "last" : "this");
        DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        report.setFrom(monday.format(df));
        report.setTo(sunday.format(df));
        report.setRealName(cu.getRealName() != null ? cu.getRealName() : cu.getUsername());
        report.setDone(done == null ? List.of() : done);
        report.setDoing(doing == null ? List.of() : doing);
        report.setExpenses(expenses == null ? List.of() : expenses);
        report.setExpenseTotal(total);
        report.setText(buildWeeklyText(report, monday, sunday));
        return ApiResponse.ok(report);
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkRecordDTO> detail(@PathVariable Long id) {
        CurrentUser cu = requireLogin();
        WorkRecord record = recordMapper.selectById(id, cu.getId());
        if (record == null) {
            throw new BizException(40001, "记录不存在");
        }
        return ApiResponse.ok(new WorkRecordDTO(record));
    }

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody CreateWorkRecordRequest req) {
        CurrentUser cu = requireLogin();
        applyCategoryTemplateRules(cu, req.getCategoryId(), req.getContent(), req.getEndTime(), req.getImageUrls());
        WorkRecord entity = new WorkRecord();
        entity.setUserId(cu.getId());
        entity.setDeptId(cu.getDeptId() == null ? 0L : cu.getDeptId());
        entity.setBizDeptId(req.getBizDeptId());
        entity.setCategoryId(req.getCategoryId());
        entity.setStatusId(req.getStatusId() == null ? 1 : req.getStatusId());
        entity.setTitle(req.getTitle());
        entity.setContent(req.getContent());
        entity.setImageUrls(req.getImageUrls());
        entity.setStartTime(req.getStartTime());
        entity.setEndTime(req.getEndTime());
        entity.setIsImportant(req.getIsImportant() == null ? 0 : req.getIsImportant());
        Integer duration = calcDurationMinutes(req.getStartTime(), req.getEndTime());
        entity.setDurationMinutes(duration);
        recordMapper.insert(entity);
        return ApiResponse.ok(Collections.singletonMap("id", entity.getId()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Boolean> update(@PathVariable Long id, @Valid @RequestBody UpdateWorkRecordRequest req) {
        CurrentUser cu = requireLogin();
        WorkRecord existed = recordMapper.selectById(id, cu.getId());
        if (existed == null) {
            throw new BizException(40001, "记录不存在");
        }
        applyCategoryTemplateRules(cu, req.getCategoryId() != null ? req.getCategoryId() : existed.getCategoryId(),
                req.getContent(), req.getEndTime(), req.getImageUrls());
        WorkRecord update = new WorkRecord();
        update.setId(id);
        update.setUserId(cu.getId());
        update.setBizDeptId(req.getBizDeptId());
        update.setCategoryId(req.getCategoryId());
        update.setStatusId(req.getStatusId());
        update.setTitle(req.getTitle());
        update.setContent(req.getContent());
        update.setImageUrls(req.getImageUrls());
        update.setStartTime(req.getStartTime());
        update.setEndTime(req.getEndTime());
        update.setIsImportant(req.getIsImportant());
        update.setDurationMinutes(calcDurationMinutes(req.getStartTime(), req.getEndTime()));
        recordMapper.update(update);
        return ApiResponse.ok(true);
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Boolean> updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateStatusRequest req) {
        CurrentUser cu = requireLogin();
        WorkRecord existed = recordMapper.selectById(id, cu.getId());
        if (existed == null) {
            throw new BizException(40001, "记录不存在");
        }
        WorkRecord update = new WorkRecord();
        update.setId(id);
        update.setUserId(cu.getId());
        update.setStatusId(req.getStatusId());
        recordMapper.update(update);
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Boolean> delete(@PathVariable Long id) {
        CurrentUser cu = requireLogin();
        recordMapper.softDelete(id, cu.getId());
        return ApiResponse.ok(true);
    }

    // --- Log APIs ---
    @GetMapping("/{id}/logs")
    public ApiResponse<List<WorkRecordLog>> getLogs(@PathVariable Long id) {
        requireRecordOwner(id);
        return ApiResponse.ok(logMapper.selectByWorkRecordId(id));
    }

    @PostMapping("/{id}/logs")
    public ApiResponse<Map<String, Long>> addLog(@PathVariable Long id, @Valid @RequestBody AddLogRequest req) {
        CurrentUser cu = requireLogin();
        requireRecordOwner(id);
        WorkRecordLog log = new WorkRecordLog();
        log.setWorkRecordId(id);
        log.setUserId(cu.getId());
        log.setLogContent(req.getLogContent());
        logMapper.insert(log);
        return ApiResponse.ok(Collections.singletonMap("id", log.getId()));
    }

    @PutMapping("/{recordId}/logs/{logId}")
    public ApiResponse<Boolean> updateLog(@PathVariable Long recordId, @PathVariable Long logId, @Valid @RequestBody AddLogRequest req) {
        requireRecordOwner(recordId);
        WorkRecordLog existedLog = logMapper.selectById(logId);
        if (existedLog == null || !Objects.equals(existedLog.getWorkRecordId(), recordId)) {
            throw new BizException(40001, "处理详情记录不存在");
        }
        WorkRecordLog updateLog = new WorkRecordLog();
        updateLog.setId(logId);
        updateLog.setLogContent(req.getLogContent());
        logMapper.update(updateLog);
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{recordId}/logs/{logId}")
    public ApiResponse<Boolean> deleteLog(@PathVariable Long recordId, @PathVariable Long logId) {
        requireRecordOwner(recordId);
        WorkRecordLog existedLog = logMapper.selectById(logId);
        if (existedLog != null && Objects.equals(existedLog.getWorkRecordId(), recordId)) {
            logMapper.deleteById(logId);
        }
        return ApiResponse.ok(true);
    }

    // --- Expense APIs ---
    @GetMapping("/{id}/expenses")
    public ApiResponse<List<WorkRecordExpense>> getExpenses(@PathVariable Long id) {
        requireRecordOwner(id);
        return ApiResponse.ok(expenseMapper.selectByWorkRecordId(id));
    }

    @PostMapping("/{id}/expenses")
    public ApiResponse<Map<String, Long>> addExpense(@PathVariable Long id, @Valid @RequestBody AddExpenseRequest req) {
        requireRecordOwner(id);
        WorkRecordExpense expense = new WorkRecordExpense();
        expense.setWorkRecordId(id);
        expense.setExpenseType(req.getExpenseType());
        expense.setAmount(req.getAmount());
        expense.setDescription(req.getDescription());
        expense.setExpenseTime(req.getExpenseTime() == null ? LocalDateTime.now() : req.getExpenseTime());
        expenseMapper.insert(expense);
        return ApiResponse.ok(Collections.singletonMap("id", expense.getId()));
    }

    @PutMapping("/{recordId}/expenses/{expenseId}")
    public ApiResponse<Boolean> updateExpense(@PathVariable Long recordId, @PathVariable Long expenseId, @Valid @RequestBody AddExpenseRequest req) {
        requireRecordOwner(recordId);
        WorkRecordExpense existedExpense = expenseMapper.selectById(expenseId);
        if (existedExpense == null || !Objects.equals(existedExpense.getWorkRecordId(), recordId)) {
            throw new BizException(40001, "费用记录不存在");
        }
        WorkRecordExpense updateExpense = new WorkRecordExpense();
        updateExpense.setId(expenseId);
        updateExpense.setExpenseType(req.getExpenseType());
        updateExpense.setAmount(req.getAmount());
        updateExpense.setDescription(req.getDescription());
        updateExpense.setExpenseTime(req.getExpenseTime());
        expenseMapper.update(updateExpense);
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/{recordId}/expenses/{expenseId}")
    public ApiResponse<Boolean> deleteExpense(@PathVariable Long recordId, @PathVariable Long expenseId) {
        requireRecordOwner(recordId);
        WorkRecordExpense existedExpense = expenseMapper.selectById(expenseId);
        if (existedExpense != null && Objects.equals(existedExpense.getWorkRecordId(), recordId)) {
            expenseMapper.deleteById(expenseId);
        }
        return ApiResponse.ok(true);
    }

    // --- Helper Methods ---
    private CurrentUser requireLogin() {
        CurrentUser cu = UserContext.get();
        if (cu == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return cu;
    }

    private void requireRecordOwner(Long recordId) {
        CurrentUser cu = requireLogin();
        WorkRecord record = recordMapper.selectById(recordId, cu.getId());
        if (record == null) {
            throw new BizException(40001, "记录不存在或无权访问");
        }
    }

    private void applyCategoryTemplateRules(CurrentUser cu, Long categoryId, String content, LocalDateTime endTime, String imageUrls) {
        if (categoryId == null) return;
        WorkCategory category = categoryMapper.selectById(categoryId, cu.getDeptId());
        if (category == null || category.getTemplateJson() == null || category.getTemplateJson().isBlank()) {
            return;
        }
        CategoryTemplate tpl;
        try {
            tpl = objectMapper.readValue(category.getTemplateJson(), CategoryTemplate.class);
        } catch (Exception e) {
            return;
        }
        if (tpl == null) return;
        if (tpl.requireContent() && (content == null || content.isBlank())) {
            throw new BizException(40001, "该分类要求填写工作内容");
        }
        if (tpl.requireEndTime() && endTime == null) {
            throw new BizException(40001, "该分类要求填写截止日期");
        }
        if (tpl.requireImage() && !hasImages(imageUrls)) {
            throw new BizException(40001, "该分类要求上传至少一张图片");
        }
    }

    private boolean hasImages(String imageUrls) {
        if (imageUrls == null || imageUrls.isBlank() || "[]".equals(imageUrls.trim())) {
            return false;
        }
        String s = imageUrls.trim();
        if (s.startsWith("[")) {
            try {
                List<?> arr = objectMapper.readValue(s, List.class);
                return arr != null && arr.stream().anyMatch(x -> x != null && !String.valueOf(x).isBlank());
            } catch (Exception ignored) {
                return true;
            }
        }
        return s.split(",").length > 0;
    }

    private String buildWeeklyText(WorkWeeklyReport report, LocalDate monday, LocalDate sunday) {
        DateTimeFormatter md = DateTimeFormatter.ofPattern("M月d日");
        StringBuilder sb = new StringBuilder();
        sb.append("【工作周报】").append(monday.format(md)).append("–").append(sunday.format(md));
        if (report.getRealName() != null && !report.getRealName().isBlank()) {
            sb.append("  ").append(report.getRealName());
        }
        sb.append("\n\n");
        sb.append("一、本周完成（").append(report.getDone().size()).append("）\n");
        appendWeeklyLines(sb, report.getDone(), false);
        sb.append("\n二、进行中（").append(report.getDoing().size()).append("）\n");
        appendWeeklyLines(sb, report.getDoing(), true);
        sb.append("\n三、费用\n");
        if (report.getExpenses() == null || report.getExpenses().isEmpty()) {
            sb.append("无\n");
        } else {
            for (WorkExpenseStat e : report.getExpenses()) {
                sb.append(e.getExpenseType() == null ? "其他" : e.getExpenseType())
                        .append(" ")
                        .append(formatMoney(e.getTotalAmount()))
                        .append("  ");
            }
            sb.append("合计 ").append(formatMoney(report.getExpenseTotal())).append("\n");
        }
        sb.append("\n（系统生成，请酌情删改）");
        return sb.toString();
    }

    private void appendWeeklyLines(StringBuilder sb, List<WorkWeeklyItem> items, boolean withDeadline) {
        if (items == null || items.isEmpty()) {
            sb.append("无\n");
            return;
        }
        DateTimeFormatter md = DateTimeFormatter.ofPattern("M/d");
        int i = 1;
        for (WorkWeeklyItem item : items) {
            sb.append(i++).append(". ");
            if (item.getCategoryName() != null && !item.getCategoryName().isBlank()) {
                sb.append("[").append(item.getCategoryName()).append("] ");
            }
            sb.append(item.getTitle() == null ? "未命名" : item.getTitle());
            if (withDeadline && item.getEndTime() != null) {
                sb.append("（截止 ").append(item.getEndTime().toLocalDate().format(md)).append("）");
            }
            if (Boolean.TRUE.equals(item.getOverdue())) {
                sb.append(" ※已逾期");
            }
            sb.append("\n");
        }
    }

    private String formatMoney(BigDecimal n) {
        if (n == null) return "0";
        return n.stripTrailingZeros().toPlainString();
    }

    private Integer calcDurationMinutes(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) return null;
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < 0) throw new BizException(40001, "结束时间不能早于开始时间");
        return minutes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) minutes;
    }

    // --- DTOs ---
    public static class AddLogRequest {
        @NotBlank
        private String logContent;

        public String getLogContent() {
            return logContent;
        }

        public void setLogContent(String logContent) {
            this.logContent = logContent;
        }
    }

    public static class AddExpenseRequest {
        @NotBlank
        private String expenseType;
        @NotNull
        private BigDecimal amount;
        private String description;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expenseTime;

        // Getters and Setters
        public String getExpenseType() {
            return expenseType;
        }

        public void setExpenseType(String expenseType) {
            this.expenseType = expenseType;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public LocalDateTime getExpenseTime() {
            return expenseTime;
        }

        public void setExpenseTime(LocalDateTime expenseTime) {
            this.expenseTime = expenseTime;
        }
    }

    public static class CreateWorkRecordRequest {
        private Long bizDeptId;
        @NotNull
        private Long categoryId;
        private Integer statusId;
        @NotBlank
        private String title;
        private String content;
        private String imageUrls;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime startTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime endTime;
        private Integer isImportant;

        // Getters and Setters
        public Long getBizDeptId() {
            return bizDeptId;
        }

        public void setBizDeptId(Long bizDeptId) {
            this.bizDeptId = bizDeptId;
        }

        public Long getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Long categoryId) {
            this.categoryId = categoryId;
        }

        public Integer getStatusId() {
            return statusId;
        }

        public void setStatusId(Integer statusId) {
            this.statusId = statusId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getImageUrls() {
            return imageUrls;
        }

        public void setImageUrls(String imageUrls) {
            this.imageUrls = imageUrls;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public void setStartTime(LocalDateTime startTime) {
            this.startTime = startTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }

        public Integer getIsImportant() {
            return isImportant;
        }

        public void setIsImportant(Integer isImportant) {
            this.isImportant = isImportant;
        }
    }

    public static class UpdateWorkRecordRequest {
        private Long bizDeptId;
        private Long categoryId;
        private Integer statusId;
        private String title;
        private String content;
        private String imageUrls;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime startTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime endTime;
        private Integer isImportant;

        // Getters and Setters
        public Long getBizDeptId() {
            return bizDeptId;
        }

        public void setBizDeptId(Long bizDeptId) {
            this.bizDeptId = bizDeptId;
        }

        public Long getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(Long categoryId) {
            this.categoryId = categoryId;
        }

        public Integer getStatusId() {
            return statusId;
        }

        public void setStatusId(Integer statusId) {
            this.statusId = statusId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getImageUrls() {
            return imageUrls;
        }

        public void setImageUrls(String imageUrls) {
            this.imageUrls = imageUrls;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public void setStartTime(LocalDateTime startTime) {
            this.startTime = startTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public void setEndTime(LocalDateTime endTime) {
            this.endTime = endTime;
        }

        public Integer getIsImportant() {
            return isImportant;
        }

        public void setIsImportant(Integer isImportant) {
            this.isImportant = isImportant;
        }
    }

    public static class UpdateStatusRequest {
        @NotNull
        private Integer statusId;

        // Getters and Setters
        public Integer getStatusId() {
            return statusId;
        }

        public void setStatusId(Integer statusId) {
            this.statusId = statusId;
        }
    }
}
