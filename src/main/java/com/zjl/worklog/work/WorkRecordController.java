package com.zjl.worklog.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.zjl.worklog.common.api.ApiResponse;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.security.DataScope;
import com.zjl.worklog.security.Permission;
import com.zjl.worklog.security.PermissionService;
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
    private final PermissionService permissions;

    public WorkRecordController(WorkRecordMapper recordMapper, WorkRecordLogMapper logMapper, WorkRecordExpenseMapper expenseMapper,
                                WorkCategoryMapper categoryMapper, ObjectMapper objectMapper, PermissionService permissions) {
        this.recordMapper = recordMapper;
        this.logMapper = logMapper;
        this.expenseMapper = expenseMapper;
        this.categoryMapper = categoryMapper;
        this.objectMapper = objectMapper;
        this.permissions = permissions;
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
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime dueBefore,
            // mine=只看本人（默认），dept=看本人所在科室；供看板切范围用
            @RequestParam(required = false) String scope
    ) {
        CurrentUser cu = requireLogin();
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        // 范围只按登录态里的科室/角色放开，deptId 不接受外部传参，否则任何人都能翻别科室的记录
        Long userId = cu.getId();
        Long deptId = null;
        if ("dept".equalsIgnoreCase(scope)) {
            // 本科室范围是「可配置门槛」：管理员若把 board.scopeDept 调高，普通员工就连同事的任务都看不到
            requirePermission(cu, Permission.BOARD_SCOPE_DEPT, "未绑定科室，无法查看科室数据");
            if (cu.getDeptId() == null) {
                throw new BizException(40002, "当前账号未绑定科室，无法查看科室数据");
            }
            userId = null;
            deptId = cu.getDeptId();
        } else if ("all".equalsIgnoreCase(scope)) {
            requirePermission(cu, Permission.BOARD_SCOPE_ALL, null);
            userId = null;
        }
        long total = recordMapper.count(userId, deptId, categoryId, categoryIds, statusIds, title, createTimeFrom, createTimeTo, isImportant, overdue, dueBefore);
        long offset = (page - 1) * size;
        List<WorkRecord> records = total == 0
                ? List.of()
                : recordMapper.selectPage(offset, size, userId, deptId, categoryId, categoryIds, statusIds, title, createTimeFrom, createTimeTo, isImportant, overdue, dueBefore);
        var views = records.stream().map(WorkRecordDTO::new).toList();
        return ApiResponse.ok(PageResponse.of(page, size, total, views));
    }

    @GetMapping("/stats/report/by-category")
    public ApiResponse<List<WorkCategoryStat>> statsByCategoryForReport(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTimeFrom,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTimeTo
    ) {
        // 个人分类统计：不分角色，永远只算自己的量（角色放开的是「看别人的记录」，不是「看别人的统计」）
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
        CurrentUser cu = requireLogin();
        // 跨科室工作量表按权限点收口，否则任何人都能靠改 deptId/userId 翻全单位的工时：
        // 拿到 report.deptCross 才可用传入的筛选条件；否则科室管理员锁本科室、普通员工只算本人
        if (permissions.allows(cu, Permission.REPORT_DEPT_CROSS)) {
            return ApiResponse.ok(recordMapper.statsUserDeptCategory(endTimeFrom, endTimeTo, deptId, userId));
        }
        if (DataScope.canViewOthers(cu)) {
            if (cu.getDeptId() == null) {
                throw new BizException(40002, "当前账号未绑定科室，无法查看科室统计");
            }
            return ApiResponse.ok(recordMapper.statsUserDeptCategory(endTimeFrom, endTimeTo, cu.getDeptId(), userId));
        }
        return ApiResponse.ok(recordMapper.statsUserDeptCategory(endTimeFrom, endTimeTo, null, cu.getId()));
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
        WorkRecord record = recordMapper.selectByIdAny(id);
        // 本科室记录人人可读（看板/列表会展示同事的任务），跨科室只有管理员能读
        if (record == null || !canRead(cu, record)) {
            throw new BizException(40001, "记录不存在");
        }
        return ApiResponse.ok(new WorkRecordDTO(record));
    }

    /** 权限点不满足就抛 40003；noDeptMessage 用于「账号没绑科室」这种要单独说明的情况 */
    private void requirePermission(CurrentUser cu, Permission permission, String noDeptMessage) {
        if (permissions.allows(cu, permission)) {
            return;
        }
        boolean noDept = noDeptMessage != null && cu.getDeptId() == null;
        throw new BizException(40003, noDept
                ? noDeptMessage
                : "该功能需要「" + permissions.minRoleLabel(permission) + "」及以上角色");
    }

    /**
     * 可读：本人 / 同科室（受 board.scopeDept 门槛）/ 跨科室（受 board.scopeAll 门槛）。
     *
     * <p>详情接口必须和列表用同一套门槛，否则把范围调严之后，
     * 列表里看不到的记录仍能被猜出 ID 直接读到，等于留了个后门。
     */
    private boolean canRead(CurrentUser cu, WorkRecord record) {
        if (record.getUserId().equals(cu.getId())) {
            return true;
        }
        if (DataScope.sameDept(cu, record.getDeptId())) {
            return permissions.allows(cu, Permission.BOARD_SCOPE_DEPT);
        }
        return permissions.allows(cu, Permission.BOARD_SCOPE_ALL);
    }

    /** 可写：本人 / 本科室的科室管理员 / 管理员。删除不走这里，见 delete 注释 */
    private WorkRecord requireWritable(Long id, CurrentUser cu) {
        WorkRecord existed = recordMapper.selectByIdAny(id);
        if (existed == null) {
            throw new BizException(40001, "记录不存在");
        }
        if (!DataScope.canEdit(cu, existed.getUserId(), existed.getDeptId(),
                permissions.minRole(Permission.RECORD_EDIT_DEPT_OTHERS))) {
            throw new BizException(40003, "只能维护自己的记录；他人记录需「"
                    + permissions.minRoleLabel(Permission.RECORD_EDIT_DEPT_OTHERS) + "」及以上角色");
        }
        return existed;
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
        WorkRecord existed = requireWritable(id, cu);
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
        requireWritable(id, cu);
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
        // 删除刻意不跟随「科室管理员可编辑」放开：改别人的任务是协作，删别人的记录是抹数据
        WorkRecord existed = recordMapper.selectByIdAny(id);
        if (existed == null) {
            throw new BizException(40001, "记录不存在");
        }
        if (!existed.getUserId().equals(cu.getId())) {
            throw new BizException(40003, "只能删除本人的记录");
        }
        // 软删影响 0 行要说出来：以前无条件回 true，前端弹「删除成功」但记录还在，等于骗人
        if (recordMapper.softDelete(id, cu.getId()) == 0) {
            throw new BizException(40001, "记录不存在或已被删除");
        }
        return ApiResponse.ok(true);
    }

    // --- Log / Expense APIs ---
    // 读走 requireReadable（与详情同口径），写走 requireWritableRecord（与主表编辑同口径）。
    @GetMapping("/{id}/logs")
    public ApiResponse<List<WorkRecordLog>> getLogs(@PathVariable Long id) {
        requireReadable(id);
        return ApiResponse.ok(logMapper.selectByWorkRecordId(id));
    }

    @PostMapping("/{id}/logs")
    public ApiResponse<Map<String, Long>> addLog(@PathVariable Long id, @Valid @RequestBody AddLogRequest req) {
        CurrentUser cu = requireLogin();
        requireWritableRecord(id);
        WorkRecordLog log = new WorkRecordLog();
        log.setWorkRecordId(id);
        log.setUserId(cu.getId());
        log.setLogContent(req.getLogContent());
        logMapper.insert(log);
        return ApiResponse.ok(Collections.singletonMap("id", log.getId()));
    }

    @PutMapping("/{recordId}/logs/{logId}")
    public ApiResponse<Boolean> updateLog(@PathVariable Long recordId, @PathVariable Long logId, @Valid @RequestBody AddLogRequest req) {
        requireWritableRecord(recordId);
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
        requireWritableRecord(recordId);
        WorkRecordLog existedLog = logMapper.selectById(logId);
        if (existedLog != null && Objects.equals(existedLog.getWorkRecordId(), recordId)) {
            logMapper.deleteById(logId);
        }
        return ApiResponse.ok(true);
    }

    // --- Expense APIs ---
    @GetMapping("/{id}/expenses")
    public ApiResponse<List<WorkRecordExpense>> getExpenses(@PathVariable Long id) {
        requireReadable(id);
        return ApiResponse.ok(expenseMapper.selectByWorkRecordId(id));
    }

    @PostMapping("/{id}/expenses")
    public ApiResponse<Map<String, Long>> addExpense(@PathVariable Long id, @Valid @RequestBody AddExpenseRequest req) {
        requireWritableRecord(id);
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
        requireWritableRecord(recordId);
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
        requireWritableRecord(recordId);
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

    /**
     * 可读一条记录：与详情接口完全同一套门槛（本人 / 本科室受 board.scopeDept 约束 / 跨科室受 board.scopeAll 约束）。
     *
     * <p>处理详情、费用这类子资源的读权限必须挂在父记录上，否则把列表范围调严之后，
     * 猜得出记录 ID 依然能读到别人的明细，等于留了个后门。
     */
    private WorkRecord requireReadable(Long recordId) {
        CurrentUser cu = requireLogin();
        WorkRecord record = recordMapper.selectByIdAny(recordId);
        if (record == null || !canRead(cu, record)) {
            throw new BizException(40001, "记录不存在或无权访问");
        }
        return record;
    }

    /**
     * 可写一条记录：直接复用主表 PUT /{id} 的 requireWritable，不再另立一套「只认本人」的口径。
     *
     * <p>之前子资源走的是 requireRecordOwner（selectById 带 user_id，只认本人），而主表走的是
     * DataScope.canEdit（本人 / 本科室达门槛的科室管理员 / 系统管理员）。两套口径并存的后果是
     * 科室管理员能改同事任务的状态和字段，却给同一条任务加不了处理详情和费用，界面只会报
     * 「记录不存在或无权访问」——同一份权限在同一个页面上时灵时不灵，比一律禁止更难排查。
     * 删除仍只允许本人，那条限制在 delete 里没有走这里，保持原样。
     */
    private WorkRecord requireWritableRecord(Long recordId) {
        return requireWritable(recordId, requireLogin());
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
