package com.zjl.worklog.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.ticket.dto.PublicSubmitRequest;
import com.zjl.worklog.ticket.dto.TicketMetaView;
import com.zjl.worklog.ticket.dto.TicketPublicView;
import com.zjl.worklog.ticket.dto.TicketView;
import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import com.zjl.worklog.ticket.entity.ServiceTicketLogEntity;
import com.zjl.worklog.ticket.entity.TicketChannelEntity;
import com.zjl.worklog.ticket.mapper.ServiceTicketLogMapper;
import com.zjl.worklog.ticket.mapper.ServiceTicketMapper;
import com.zjl.worklog.ticket.mapper.TicketChannelMapper;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import com.zjl.worklog.work.entity.WorkCategory;
import com.zjl.worklog.work.entity.WorkRecord;
import com.zjl.worklog.work.mapper.WorkCategoryMapper;
import com.zjl.worklog.work.mapper.WorkRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 问题工单核心服务：提交、状态机、以及受理后生成工作记录。
 *
 * <p>并发一律用「带前置状态的 CAS 更新 + 影响行数判断」实现，不用悲观锁：
 * 受理高峰是多人抢单，CAS 失败方收到「状态已变化」提示比阻塞等待更合适。
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /** 每个工单最多几张图，手机端与提交校验共用同一个值 */
    public static final int MAX_IMAGES = 9;

    /** 报修人在完成后可退回的时间窗（小时） */
    private static final long REOPEN_WINDOW_HOURS = 24L;

    private final ServiceTicketMapper ticketMapper;
    private final ServiceTicketLogMapper logMapper;
    private final TicketChannelMapper channelMapper;
    private final TicketChannelService channelService;
    private final TicketRateLimiter rateLimiter;
    private final TicketFormTokenService formTokenService;
    private final TicketNoGenerator noGenerator;
    private final WorkCategoryMapper categoryMapper;
    private final WorkRecordMapper recordMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    /** 图片 key 的根前缀，与 OSS 配置保持一致 */
    @Value("${aliyun.oss.dir-prefix:}")
    private String ossDirPrefix;

    /** 单 IP 每 10 分钟允许的提交次数 */
    @Value("${ticket.rate.ip-count:8}")
    private int ipLimitCount;
    @Value("${ticket.rate.ip-window-seconds:600}")
    private long ipLimitWindow;
    /** 单渠道每分钟允许的提交次数（内部员工场景，同事可能同办公室同 IP，放宽于此） */
    @Value("${ticket.rate.channel-qpm:60}")
    private int channelQpm;
    /** 同一手机号每小时上限 */
    @Value("${ticket.rate.phone-hour:5}")
    private int phoneHourLimit;

    public TicketService(ServiceTicketMapper ticketMapper,
                         ServiceTicketLogMapper logMapper,
                         TicketChannelMapper channelMapper,
                         TicketChannelService channelService,
                         TicketRateLimiter rateLimiter,
                         TicketFormTokenService formTokenService,
                         TicketNoGenerator noGenerator,
                         WorkCategoryMapper categoryMapper,
                         WorkRecordMapper recordMapper,
                         UserMapper userMapper,
                         ObjectMapper objectMapper) {
        this.ticketMapper = ticketMapper;
        this.logMapper = logMapper;
        this.channelMapper = channelMapper;
        this.channelService = channelService;
        this.rateLimiter = rateLimiter;
        this.formTokenService = formTokenService;
        this.noGenerator = noGenerator;
        this.categoryMapper = categoryMapper;
        this.recordMapper = recordMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ 手机端

    /** 表单初始化：只暴露展示字段 */
    public TicketMetaView meta(TicketChannelEntity channel) {
        TicketMetaView view = new TicketMetaView();
        view.setChannelCode(channel.getChannelCode());
        view.setChannelName(channel.getChannelName());
        view.setNeedPhone(channel.getNeedPhone() != null && channel.getNeedPhone() == 1);
        view.setMaxImages(MAX_IMAGES);

        List<TicketMetaView.CategoryOption> options = new ArrayList<>();
        for (WorkCategory c : categoryMapper.selectAllEnabled(channel.getDeptId())) {
            options.add(new TicketMetaView.CategoryOption(c.getId(), c.getCategoryName()));
        }
        view.setCategories(options);
        view.setUrgencies(List.of(
                new TicketMetaView.UrgencyOption(1, "紧急", slaHours(1)),
                new TicketMetaView.UrgencyOption(2, "普通", slaHours(2)),
                new TicketMetaView.UrgencyOption(3, "一般", slaHours(3))
        ));
        return view;
    }

    /**
     * 报修人提交问题。
     *
     * @return 单号 + 一次性查询令牌；命中蜜罐时返回「看起来成功」的假结果
     */
    public SubmitResult submit(PublicSubmitRequest req, String ip, String userAgent) {
        // 1. 蜜罐：有值即机器人。不报错、不落库，返回假单号，避免给对方探测反馈
        if (StringUtils.hasText(req.getWebsite())) {
            log.warn("命中蜜罐字段，疑似脚本提交: ip={}", ip);
            return SubmitResult.fake("ST" + TicketTokens.randomHex(6).toUpperCase(), TicketTokens.randomHex(16));
        }

        // 2. 填表凭证：令牌里记录的渠道才是本次提交的渠道，客户端传来的任何渠道信息都不作数
        Long channelId = formTokenService.require(req.getFormToken());
        TicketChannelEntity channel = channelService.requireEnabledById(channelId);

        // 3. 频率限制：内存窗口 + 渠道日配额（DB 兜底，重启不丢）
        if (!rateLimiter.acquire("submit:ip:" + ip, ipLimitCount, ipLimitWindow)) {
            throw new BizException(42901, "提交过于频繁，请稍后再试");
        }
        if (!rateLimiter.acquire("submit:ch:" + channel.getId(), channelQpm, 60)) {
            throw new BizException(42902, "当前登记入口提交过于集中，请稍后再试");
        }
        if (StringUtils.hasText(req.getContactPhone())
                && !rateLimiter.acquire("submit:ph:" + TicketTokens.sha256Hex(req.getContactPhone()).substring(0, 16),
                phoneHourLimit, 3600)) {
            throw new BizException(42903, "该手机号提交过于频繁，请联系科室管理员");
        }
        int dailyLimit = channel.getDailyLimit() == null ? 200 : channel.getDailyLimit();
        if (dailyLimit > 0 && ticketMapper.countTodayByChannel(channel.getId()) >= dailyLimit) {
            throw new BizException(42904, "今日该登记入口已达提交上限，请电话联系科室");
        }

        // 4. 必填规则（内部员工场景默认不强制手机号，由渠道配置决定）
        if (channel.getNeedPhone() != null && channel.getNeedPhone() == 1 && !StringUtils.hasText(req.getContactPhone())) {
            throw new BizException(40001, "请填写联系电话");
        }

        // 5. 问题类型必须属于本渠道科室，防止跨科室注入
        Long categoryId = req.getCategoryId();
        if (categoryId != null) {
            WorkCategory category = categoryMapper.selectById(categoryId, channel.getDeptId());
            if (category == null) {
                throw new BizException(40001, "问题类型无效，请重新选择");
            }
        } else {
            categoryId = channel.getDefaultCategoryId();
        }

        // 6. 图片 key 必须落在本渠道目录下，否则等于借别人的工单读别人的附件
        List<String> keys = normalizeImageKeys(req.getImageKeys(), channel.getChannelCode());

        // 7. 落库：单号撞唯一键时重试（并发提交在同一秒内可能发生）
        String accessToken = TicketTokens.randomToken();
        ServiceTicketEntity entity = new ServiceTicketEntity();
        entity.setChannelId(channel.getId());
        entity.setDeptId(channel.getDeptId());
        entity.setCategoryId(categoryId);
        entity.setStatus(TicketStatus.PENDING.code());
        entity.setUrgency(normalizeUrgency(req.getUrgency()));
        entity.setTitle(req.getTitle().trim());
        entity.setContent(req.getContent().trim());
        entity.setLocation(trimToNull(req.getLocation()));
        entity.setContactName(trimToNull(req.getContactName()));
        entity.setContactPhone(trimToNull(req.getContactPhone()));
        entity.setImageUrls(writeJson(keys));
        entity.setAccessTokenHash(TicketTokens.sha256Hex(accessToken));
        entity.setSubmitIp(ip);
        entity.setSubmitUa(abbreviate(userAgent, 255));
        entity.setDueTime(LocalDateTime.now().plusHours(slaHours(entity.getUrgency())));

        boolean inserted = false;
        for (int attempt = 0; attempt < 3 && !inserted; attempt++) {
            entity.setTicketNo(noGenerator.next());
            try {
                ticketMapper.insert(entity);
                inserted = true;
            } catch (DuplicateKeyException e) {
                log.warn("单号冲突，重试第 {} 次", attempt + 1);
            }
        }
        if (!inserted) {
            throw new BizException(50001, "提交失败，请稍后重试");
        }

        // 提交成功后立即作废填表凭证：同一令牌不能二次提交，防止重放与误双击
        formTokenService.consume(req.getFormToken());

        writeLog(entity.getId(), 0, null, firstNonBlank(entity.getContactName(), "报修人"),
                "SUBMIT", "提交问题登记", entity.getImageUrls(), 1);

        return SubmitResult.real(entity.getTicketNo(), accessToken);
    }

    /** 校验报修人查询令牌，返回工单；令牌只存哈希，因此这里做定长摘要比较 */
    public ServiceTicketEntity requireVisitorAccess(String ticketNo, String accessToken) {
        ServiceTicketEntity ticket = StringUtils.hasText(ticketNo) ? ticketMapper.selectByNo(ticketNo.trim().toUpperCase()) : null;
        if (ticket == null || !StringUtils.hasText(ticket.getAccessTokenHash()) || !StringUtils.hasText(accessToken)) {
            throw new BizException(40301, "单号或查询凭证无效");
        }
        if (!ticket.getAccessTokenHash().equals(TicketTokens.sha256Hex(accessToken.trim()))) {
            throw new BizException(40301, "单号或查询凭证无效");
        }
        return ticket;
    }

    /** 报修人查看进度：只返回对其可见的流转记录 */
    public TicketPublicView publicDetail(ServiceTicketEntity ticket) {
        TicketPublicView view = TicketPublicView.of(ticket, null);
        TicketChannelEntity viewChannel = channelMapper.selectById(ticket.getChannelId());
        if (viewChannel != null) {
            view.setChannelCode(viewChannel.getChannelCode());
        }
        view.setImages(readJson(ticket.getImageUrls()));
        List<TicketPublicView.LogItem> items = new ArrayList<>();
        for (ServiceTicketLogEntity l : logMapper.selectVisibleByTicketId(ticket.getId())) {
            TicketPublicView.LogItem item = new TicketPublicView.LogItem();
            item.setAction(l.getAction());
            item.setOperatorName(l.getOperatorName());
            item.setOperatorType(l.getOperatorType());
            item.setRemark(l.getRemark());
            item.setImages(readJson(l.getImageUrls()));
            item.setCreateTime(l.getCreateTime());
            items.add(item);
        }
        view.setLogs(items);
        LocalDateTime from = ticket.getAcceptTime() != null ? ticket.getAcceptTime() : ticket.getCreateTime();
        LocalDateTime to = ticket.getDoneTime() != null ? ticket.getDoneTime() : LocalDateTime.now();
        view.setWaitedMinutes(Duration.between(from, to).toMinutes());
        return view;
    }

    /** 报修人补充说明/补图 */
    public void reporterReply(ServiceTicketEntity ticket, String remark, List<String> imageKeys) {
        TicketChannelEntity channel = channelMapper.selectById(ticket.getChannelId());
        if (channel == null) {
            throw new BizException(40001, "登记渠道已不存在");
        }
        if (!rateLimiter.acquire("reply:tk:" + ticket.getTicketNo(), 10, 600)) {
            throw new BizException(42901, "操作过于频繁，请稍后再试");
        }
        List<String> keys = normalizeImageKeys(imageKeys, channel.getChannelCode());
        writeLog(ticket.getId(), 0, null, "报修人", "REPLY", remark, writeJson(keys), 1);

        // 退回状态下报修人补充说明，等同于重新进入待受理队列
        if (TicketStatus.REJECTED.code() == nz(ticket.getStatus())) {
            int updated = ticketMapper.updateStatusCas(ticket.getId(),
                    List.of(TicketStatus.REJECTED.code()), TicketStatus.PENDING.code(),
                    null, null, null, null, null);
            if (updated == 0) {
                throw new BizException(40001, "工单状态已变化，请刷新后查看");
            }
        }
    }

    /** 报修人确认完成：20 -> 30 */
    public void reporterConfirm(ServiceTicketEntity ticket) {
        cas(ticket.getId(), List.of(TicketStatus.WAIT_CONFIRM.code()), TicketStatus.DONE.code(),
                null, null, null, null, LocalDateTime.now());
        writeLog(ticket.getId(), 0, null, "报修人", "CONFIRM", "报修人确认已解决", null, 1);
    }

    /** 报修人退回：20 -> 10，仅在完成后 24 小时内允许 */
    public void reporterReopen(ServiceTicketEntity ticket, String reason) {
        if (ticket.getDoneTime() == null
                || ticket.getDoneTime().plusHours(REOPEN_WINDOW_HOURS).isBefore(LocalDateTime.now())) {
            throw new BizException(40001, "已超过可退回的时间窗，请重新登记");
        }
        cas(ticket.getId(), List.of(TicketStatus.WAIT_CONFIRM.code()), TicketStatus.PROCESSING.code(),
                ticket.getAssigneeId(), ticket.getAssigneeName(), null, null, null);
        // 退回后 done_time 需要清空，否则列表仍显示完成时间；单独一条 UPDATE 语义更清楚
        ticketMapper.clearDoneTime(ticket.getId());
        writeLog(ticket.getId(), 0, null, "报修人", "REOPEN",
                firstNonBlank(reason, "报修人反馈问题未解决"), null, 1);
    }

    /** 报修人评价，仅一次 */
    public void reporterRate(ServiceTicketEntity ticket, Integer rating, String comment) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new BizException(40001, "评分需为 1~5");
        }
        if (TicketStatus.DONE.code() != nz(ticket.getStatus()) && TicketStatus.CLOSED.code() != nz(ticket.getStatus())) {
            throw new BizException(40001, "工单完成后才能评价");
        }
        if (ticketMapper.updateRating(ticket.getId(), rating, abbreviate(comment, 255)) == 0) {
            throw new BizException(40001, "该工单已评价过");
        }
        writeLog(ticket.getId(), 0, null, "报修人", "RATE", "评分 " + rating + (StringUtils.hasText(comment) ? "：" + comment : ""), null, 1);
    }

    /**
     * 校验某张图片是否属于该工单，属于则原样返回 key，否则返回 null。
     * 图片代理接口用它把「能看这张工单」收窄成「只能看这张工单自己的附件」。
     */
    public String findImage(ServiceTicketEntity ticket, String key) {
        if (ticket == null || !StringUtils.hasText(key)) {
            return null;
        }
        String target = key.trim();
        for (String k : readJson(ticket.getImageUrls())) {
            if (target.equals(k)) {
                return target;
            }
        }
        return null;
    }
    // ------------------------------------------------------------ 受理台

    public PageResponse<TicketView> page(Long deptId, List<Integer> statusList, Long categoryId, Long assigneeId,
                                         String keyword, Boolean overdue,
                                         LocalDateTime from, LocalDateTime to, long page, long size) {
        if (deptId == null) {
            // 没有科室信息就完全没有可见范围，宁可返回空也不放大权限
            return PageResponse.of(page, size, 0, List.of());
        }
        long total = ticketMapper.count(deptId, statusList, categoryId, assigneeId, keyword, overdue, from, to);
        if (total == 0) {
            return PageResponse.of(page, size, 0, List.of());
        }
        List<ServiceTicketEntity> rows = ticketMapper.selectPage((page - 1) * size, size,
                deptId, statusList, categoryId, assigneeId, keyword, overdue, from, to);
        List<TicketView> views = new ArrayList<>(rows.size());
        for (ServiceTicketEntity e : rows) {
            views.add(toView(e, false));
        }
        return PageResponse.of(page, size, total, views);
    }

    /** 待办角标：本科室待受理 + 指派给我的未完成 */
    public PendingCount pendingCount(Long deptId, Long userId) {
        if (deptId == null) {
            return new PendingCount(0, 0);
        }
        long deptPending = ticketMapper.count(deptId, List.of(TicketStatus.PENDING.code()),
                null, null, null, null, null, null);
        long mine = ticketMapper.count(deptId, TicketStatus.openCodes(), userId, null, null, null, null, null);
        return new PendingCount(deptPending, mine);
    }

    public TicketView detail(ServiceTicketEntity ticket) {
        TicketView view = toView(ticket, true);
        List<TicketView.LogLine> lines = new ArrayList<>();
        for (ServiceTicketLogEntity l : logMapper.selectByTicketId(ticket.getId())) {
            TicketView.LogLine line = new TicketView.LogLine();
            line.setId(l.getId());
            line.setAction(l.getAction());
            line.setOperatorType(l.getOperatorType());
            line.setOperatorName(l.getOperatorName());
            line.setRemark(l.getRemark());
            line.setImages(readJson(l.getImageUrls()));
            line.setVisibleToReporter(l.getVisibleToReporter());
            line.setCreateTime(l.getCreateTime());
            lines.add(line);
        }
        view.setLogs(lines);
        return view;
    }

    /** 科室边界：工单不属于该科室就当作不存在，不给「存在但无权」的信息泄露 */
    public ServiceTicketEntity requireInDept(Long ticketId, Long deptId) {
        ServiceTicketEntity ticket = ticketId == null ? null : ticketMapper.selectById(ticketId);
        if (ticket == null || (deptId != null && !deptId.equals(ticket.getDeptId()))) {
            throw new BizException(40401, "工单不存在或无权访问");
        }
        return ticket;
    }

    /**
     * 受理：置为处理中、认领到自己，并在同一事务里生成对应工作记录。
     * 工作记录插入失败会整体回滚，避免出现「工单已受理但工作量没记上」。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long accept(ServiceTicketEntity ticket, CurrentUser cu) {
        int updated = ticketMapper.updateStatusCas(ticket.getId(),
                List.of(TicketStatus.PENDING.code()), TicketStatus.PROCESSING.code(),
                cu.getId(), cu.getRealName(), LocalDateTime.now(), null, null);
        if (updated == 0) {
            throw new BizException(40001, "工单已被他人受理，请刷新后查看");
        }
        writeLog(ticket.getId(), 1, cu.getId(), cu.getRealName(), "ACCEPT", "已受理", null, 1);
        return createWorkRecord(ticket, cu);
    }

    /** 派单给本科室其他同事 */
    public void assign(ServiceTicketEntity ticket, Long toUserId, String remark, CurrentUser cu) {
        UserEntity target = toUserId == null ? null : userMapper.selectById(toUserId);
        if (target == null || (target.getStatus() != null && target.getStatus() == 0)) {
            throw new BizException(40001, "接收人不存在或已禁用");
        }
        if (target.getDeptId() == null || !target.getDeptId().equals(ticket.getDeptId())) {
            throw new BizException(40001, "只能派给本受理科室的同事");
        }
        cas(ticket.getId(), List.of(TicketStatus.PENDING.code(), TicketStatus.PROCESSING.code()),
                TicketStatus.PROCESSING.code(), target.getId(), target.getRealName(),
                ticket.getAcceptTime() == null ? LocalDateTime.now() : ticket.getAcceptTime(), null, null);
        writeLog(ticket.getId(), 1, cu.getId(), cu.getRealName(), "ASSIGN",
                "派单给 " + target.getRealName() + (StringUtils.hasText(remark) ? "：" + remark : ""), null, 1);
    }

    /** 回复：visibleToReporter=0 时为仅内部可见的备注 */
    public void reply(ServiceTicketEntity ticket, String remark, List<String> images, boolean visible, CurrentUser cu) {
        if (!StringUtils.hasText(remark)) {
            throw new BizException(40001, "请填写回复内容");
        }
        String keys = null;
        if (images != null && !images.isEmpty()) {
            TicketChannelEntity channel = channelMapper.selectById(ticket.getChannelId());
            keys = writeJson(normalizeImageKeys(images, channel == null ? "" : channel.getChannelCode()));
        }
        writeLog(ticket.getId(), 1, cu.getId(), cu.getRealName(), "REPLY", remark, keys, visible ? 1 : 0);
    }

    /** 处理完成，等待报修人确认：10 -> 20 */
    public void done(ServiceTicketEntity ticket, String remark, CurrentUser cu) {
        cas(ticket.getId(), List.of(TicketStatus.PROCESSING.code()), TicketStatus.WAIT_CONFIRM.code(),
                null, null, null, LocalDateTime.now(), null);
        writeLog(ticket.getId(), 1, cu.getId(), cu.getRealName(), "DONE",
                firstNonBlank(remark, "处理完成，待报修人确认"), null, 1);
    }

    /** 退回：0/10 -> 50，必须给理由（会展示给报修人） */
    public void reject(ServiceTicketEntity ticket, String reason, CurrentUser cu) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(40001, "退回必须填写理由");
        }
        cas(ticket.getId(), List.of(TicketStatus.PENDING.code(), TicketStatus.PROCESSING.code()),
                TicketStatus.REJECTED.code(), null, null, null, null, null);
        writeLog(ticket.getId(), 1, cu.getId(), cu.getRealName(), "REJECT", reason, null, 1);
    }

    /** 归档：30/50 -> 40 */
    public void close(ServiceTicketEntity ticket, CurrentUser cu) {
        cas(ticket.getId(), List.of(TicketStatus.DONE.code(), TicketStatus.REJECTED.code()),
                TicketStatus.CLOSED.code(), null, null, null, null, LocalDateTime.now());
        writeLog(ticket.getId(), 1, cu.getId(), cu.getRealName(), "CLOSE", "工单归档", null, 1);
    }

    /** 补生成/重新生成工作记录，幂等 */
    @Transactional(rollbackFor = Exception.class)
    public Long toRecord(ServiceTicketEntity ticket, CurrentUser cu) {
        if (ticket.getWorkRecordId() != null) {
            return ticket.getWorkRecordId();
        }
        return createWorkRecord(ticket, cu);
    }

    // ------------------------------------------------------------ 内部工具

    private void cas(Long ticketId, List<Integer> expected, int toStatus,
                     Long assigneeId, String assigneeName,
                     LocalDateTime acceptTime, LocalDateTime doneTime, LocalDateTime closeTime) {
        int updated = ticketMapper.updateStatusCas(ticketId, expected, toStatus,
                assigneeId, assigneeName, acceptTime, doneTime, closeTime);
        if (updated == 0) {
            throw new BizException(40001, "工单状态已变化，请刷新后重试");
        }
    }

    /**
     * 由工单生成工作记录。
     *
     * <p>不套用「分类模板必填校验」（applyCategoryTemplateRules）：那套规则约束的是人工登记时的填报规范，
     * 而工单在提交阶段已经完成了自己的必填校验；若在此套用，报修人没传图就会让受理直接失败，
     * 等于把外部输入的质量问题变成科室无法受理的故障。这里把 endTime 设成 SLA 时间，语义正好一致。
     */
    private Long createWorkRecord(ServiceTicketEntity ticket, CurrentUser cu) {
        if (ticket.getWorkRecordId() != null) {
            return ticket.getWorkRecordId();
        }
        TicketChannelEntity channel = channelMapper.selectById(ticket.getChannelId());
        Long categoryId = ticket.getCategoryId() != null
                ? ticket.getCategoryId()
                : (channel == null ? null : channel.getDefaultCategoryId());

        WorkRecord record = new WorkRecord();
        record.setUserId(cu.getId());
        record.setDeptId(cu.getDeptId() == null ? 0L : cu.getDeptId());
        // 业务科室 = 报修受理科室，正好复用现有字段，本科室的工作量统计口径不变
        record.setBizDeptId(ticket.getDeptId());
        record.setCategoryId(categoryId);
        record.setStatusId(1);
        record.setTitle(abbreviate("[#" + ticket.getTicketNo() + "] " + ticket.getTitle(), 200));
        record.setContent(buildRecordContent(ticket));
        record.setImageUrls(ticket.getImageUrls());
        record.setStartTime(ticket.getCreateTime() == null ? LocalDateTime.now() : ticket.getCreateTime());
        record.setEndTime(ticket.getDueTime() == null
                ? LocalDateTime.now().plusHours(slaHours(ticket.getUrgency()))
                : ticket.getDueTime());
        // 紧急工单在待办列表里要能被标红，沿用工作记录既有的 is_important 语义
        record.setIsImportant(nz(ticket.getUrgency()) == 1 ? 1 : 0);
        record.setSourceType("TICKET");
        record.setSourceId(ticket.getId());
        recordMapper.insert(record);

        ticketMapper.bindWorkRecord(ticket.getId(), record.getId());
        writeLog(ticket.getId(), 2, cu.getId(), cu.getRealName(), "ACCEPT",
                "已生成工作记录 #" + record.getId(), null, 0);
        return record.getId();
    }

    private String buildRecordContent(ServiceTicketEntity ticket) {
        StringBuilder sb = new StringBuilder();
        sb.append("【手机端问题登记】\n");
        if (StringUtils.hasText(ticket.getLocation())) {
            sb.append("地点：").append(ticket.getLocation()).append('\n');
        }
        if (StringUtils.hasText(ticket.getContactName())) {
            sb.append("报修人：").append(ticket.getContactName());
            if (StringUtils.hasText(ticket.getContactPhone())) {
                sb.append(' ').append(ticket.getContactPhone());
            }
            sb.append('\n');
        }
        sb.append("问题描述：\n").append(ticket.getContent());
        return sb.toString();
    }

    private TicketView toView(ServiceTicketEntity e, boolean withChannelName) {
        TicketView view = TicketView.of(e);
        view.setImages(readJson(e.getImageUrls()));
        if (withChannelName && e.getChannelId() != null) {
            TicketChannelEntity channel = channelMapper.selectById(e.getChannelId());
            view.setChannelName(channel == null ? null : channel.getChannelName());
            view.setChannelCode(channel == null ? null : channel.getChannelCode());
        }
        if (e.getCategoryId() != null && e.getDeptId() != null) {
            WorkCategory category = categoryMapper.selectById(e.getCategoryId(), e.getDeptId());
            view.setCategoryName(category == null ? null : category.getCategoryName());
        }
        if (e.getDueTime() != null) {
            boolean settled = TicketStatus.of(e.getStatus()) != null && TicketStatus.of(e.getStatus()).isFinal();
            LocalDateTime to = settled && e.getCloseTime() != null ? e.getCloseTime() : LocalDateTime.now();
            view.setSlaRemainMinutes(Duration.between(to, e.getDueTime()).toMinutes());
        }
        return view;
    }

    private List<String> normalizeImageKeys(List<String> keys, String channelCode) {
        List<String> result = new ArrayList<>();
        if (keys == null) {
            return result;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            // 统一存 objectKey：完整 URL 会随域名变化而失效，也不能用来做前缀校验
            String k = TicketImageKeys.toKey(key);
            if (k.isEmpty()) {
                continue;
            }
            if (!TicketImageKeys.belongsToChannel(ossDirPrefix, channelCode, k)) {
                throw new BizException(40303, "图片不属于当前登记入口，请重新上传");
            }
            if (!result.contains(k)) {
                result.add(k);
            }
            if (result.size() > MAX_IMAGES) {
                throw new BizException(40001, "最多上传 " + MAX_IMAGES + " 张图片");
            }
        }
        return result;
    }

    private void writeLog(Long ticketId, int operatorType, Long operatorId, String operatorName,
                          String action, String remark, String imageUrls, int visibleToReporter) {
        ServiceTicketLogEntity entry = new ServiceTicketLogEntity();
        entry.setTicketId(ticketId);
        entry.setOperatorType(operatorType);
        entry.setOperatorId(operatorId);
        entry.setOperatorName(operatorName);
        entry.setAction(action);
        entry.setRemark(remark);
        entry.setImageUrls(imageUrls);
        entry.setVisibleToReporter(visibleToReporter);
        logMapper.insert(entry);
    }

    /** 紧急度 -> SLA 小时 */
    static int slaHours(Integer urgency) {
        return switch (nz(urgency)) {
            case 1 -> 4;
            case 3 -> 72;
            default -> 24;
        };
    }

    private static Integer normalizeUrgency(Integer urgency) {
        int u = nz(urgency);
        return (u >= 1 && u <= 3) ? u : 2;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private String writeJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("图片列表序列化失败，按空处理", e);
            return null;
        }
    }

    private List<String> readJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            // 兼容历史上直接存单个 URL 或逗号分隔的数据
            List<String> fallback = new ArrayList<>();
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    fallback.add(p);
                }
            }
            return fallback;
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 提交结果：fake=true 表示命中蜜罐，调用方给前端展示成功但不落库 */
    public static class SubmitResult {
        private final String ticketNo;
        private final String accessToken;
        private final boolean fake;

        private SubmitResult(String ticketNo, String accessToken, boolean fake) {
            this.ticketNo = ticketNo;
            this.accessToken = accessToken;
            this.fake = fake;
        }

        static SubmitResult real(String ticketNo, String accessToken) {
            return new SubmitResult(ticketNo, accessToken, false);
        }

        static SubmitResult fake(String ticketNo, String accessToken) {
            return new SubmitResult(ticketNo, accessToken, true);
        }

        public String getTicketNo() {
            return ticketNo;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public boolean isFake() {
            return fake;
        }
    }

    /** 待办角标 */
    public static class PendingCount {
        private final long deptPending;
        private final long mine;

        public PendingCount(long deptPending, long mine) {
            this.deptPending = deptPending;
            this.mine = mine;
        }

        public long getDeptPending() {
            return deptPending;
        }

        public long getMine() {
            return mine;
        }
    }
}
