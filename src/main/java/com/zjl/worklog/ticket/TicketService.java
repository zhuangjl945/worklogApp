package com.zjl.worklog.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjl.worklog.common.api.PageResponse;
import com.zjl.worklog.common.exception.BizException;
import com.zjl.worklog.security.CurrentUser;
import com.zjl.worklog.ticket.dto.PublicSubmitRequest;
import com.zjl.worklog.ticket.dto.TicketFlowRules;
import com.zjl.worklog.ticket.dto.TicketFormRules;
import com.zjl.worklog.ticket.dto.TicketMetaView;
import com.zjl.worklog.ticket.dto.TicketPublicView;
import com.zjl.worklog.ticket.dto.TicketView;
import com.zjl.worklog.ticket.entity.ServiceTicketEntity;
import com.zjl.worklog.ticket.entity.ServiceTicketLogEntity;
import com.zjl.worklog.ticket.entity.TicketChannelEntity;
import com.zjl.worklog.ticket.mapper.ServiceTicketLogMapper;
import com.zjl.worklog.ticket.mapper.ServiceTicketMapper;
import com.zjl.worklog.config.mapper.SysConfigMapper;
import com.zjl.worklog.config.SysConfigService;
import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.ticket.mapper.TicketChannelMapper;
import com.zjl.worklog.dept.entity.DeptEntity;
import com.zjl.worklog.dept.mapper.DeptMapper;
import com.zjl.worklog.user.entity.UserEntity;
import com.zjl.worklog.user.mapper.UserMapper;
import com.zjl.worklog.work.entity.WorkCategory;
import com.zjl.worklog.work.entity.WorkRecord;
import com.zjl.worklog.work.mapper.WorkCategoryMapper;
import com.zjl.worklog.work.mapper.WorkRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 每个工单最多几张图的硬上限。
     *
     * <p>实际生效的上限已经参数化（参数配置 → 登记表单 → max_images），
     * 这个常量只作为「参数也休想突破」的天花板：手机端压缩与 OSS 目录都按 9 张设计。
     */
    public static final int MAX_IMAGES = TicketFormRules.IMAGES_HARD_CAP;

    /** 报修人在完成后可退回的时间窗（小时） */
    private static final long REOPEN_WINDOW_HOURS = 24L;

    /**
     * 自动确认每轮最多处理多少条。
     *
     * <p>定时任务 5 分钟一轮，正常量级远到不了这个数；留上限是为了「参数刚打开、积压了一夜」时
     * 一轮跑不完也不会把一次调度变成大批量更新，剩下的下一轮继续。
     */
    private static final int AUTO_CONFIRM_BATCH = 200;

    /**
     * 流转参数的短时缓存（毫秒）。
     *
     * <p>列表页每行都要算「还剩多久自动确认」，不加这层缓存会退化成「一页 20 行 = 20 次 sys_config 查询」。
     * 30 秒的滞后对以小时计的业务时限毫无影响，管理员在参数页改完也不必重启服务。
     * 这里不抢锁：并发最坏是两个线程各查一次库、再写入同一份语义的快照，读到的始终是完整对象。
     */
    private static final long FLOW_RULES_CACHE_MS = 30_000L;
    private volatile TicketFlowRules cachedFlowRules;
    private volatile long cachedFlowRulesAt;

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
    private final DeptMapper deptMapper;
    private final SysConfigMapper sysConfigMapper;
    private final SysConfigService sysConfig;
    private final ObjectMapper objectMapper;

    /** 图片 key 的根前缀，与 OSS 配置保持一致 */
    @Value("${aliyun.oss.dir-prefix:}")
    private String ossDirPrefix;

    /*
     * 下面四个 @Value 是「兜底值」，不是唯一来源：真正生效的值每次现读
     * 「参数配置 → 限流防护」（见 ipLimitCountNow() 等）。yml 只在 sys_config 缺项、
     * 值不合法或查库失败时顶上，所以运维既能在页面上调，也有一份能写进版本库的默认值。
     */
    /** 单 IP 每窗口允许的提交次数（兜底值） */
    @Value("${ticket.rate.ip-count:8}")
    private int ipLimitCount;
    /** 单 IP 限流窗口长度，秒（兜底值） */
    @Value("${ticket.rate.ip-window-seconds:600}")
    private long ipLimitWindow;
    /** 单渠道每分钟允许的提交次数（兜底值；内部员工场景，同事同办公室同 IP，放宽于此） */
    @Value("${ticket.rate.channel-qpm:60}")
    private int channelQpm;
    /** 同一联系电话每小时上限（兜底值） */
    @Value("${ticket.rate.phone-hour:5}")
    private int phoneHourLimit;

    /**
     * 生效中的单 IP 提交次数上限。
     *
     * <p>下限夹到 1 是有原因的：滑动窗口里 acquire 判的是 size >= maxCount，
     * maxCount 为 0 或负数时恒为「已满」，等于在参数页填个 0 就把手机端报修通道整个关死，
     * 而现象看起来像系统故障。想真正关闭限流不该靠这个值，请走渠道停用。
     */
    private int ipLimitCountNow() {
        return clamp(sysConfig.getInt(SysConfigService.GROUP_RATE_LIMIT,
                SysConfigService.KEY_IP_COUNT, ipLimitCount), 1, 100_000);
    }

    /** 生效中的单 IP 窗口长度（秒）：最短 1 秒，最长一天 */
    private long ipLimitWindowNow() {
        return clamp(sysConfig.getLong(SysConfigService.GROUP_RATE_LIMIT,
                SysConfigService.KEY_IP_WINDOW_SECONDS, ipLimitWindow), 1L, 86_400L);
    }

    /** 生效中的单渠道每分钟上限 */
    private int channelQpmNow() {
        return clamp(sysConfig.getInt(SysConfigService.GROUP_RATE_LIMIT,
                SysConfigService.KEY_CHANNEL_QPM, channelQpm), 1, 100_000);
    }

    /** 生效中的同手机号每小时上限 */
    private int phoneHourNow() {
        return clamp(sysConfig.getInt(SysConfigService.GROUP_RATE_LIMIT,
                SysConfigService.KEY_PHONE_HOUR, phoneHourLimit), 1, 100_000);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
    /**
     * 查询密码连续输错多少次就锁住这个单号。
     *
     * <p>单号是可预测的短流水，密码只有 6 位数字，能不能防住遍历全凭这道锁定：
     * 5 次 / 15 分钟意味着猜完一单要 50 天，而 15 分钟内横扫全库要几万次请求，
     * 两条维度叠起来就把「猜号」和「猜密码」都变成了不划算的事。
     */
    @Value("${ticket.query.fail-per-no:5}")
    private int queryFailPerNo;
    /** 同一个 IP 在锁定期内跨单号允许的失败次数，挡的是「每个号试两下」的横扫 */
    @Value("${ticket.query.fail-per-ip:30}")
    private int queryFailPerIp;
    /** 上面两个维度共用的滑动窗口长度（秒） */
    @Value("${ticket.query.lock-window-seconds:900}")
    private long queryLockWindow;

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
                         DeptMapper deptMapper,
                          SysConfigMapper sysConfigMapper,
                          SysConfigService sysConfig,
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
        this.deptMapper = deptMapper;
        this.sysConfigMapper = sysConfigMapper;
        this.sysConfig = sysConfig;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ 手机端

    /** 表单初始化：只暴露展示字段 */
    public TicketMetaView meta(TicketChannelEntity channel) {
        TicketMetaView view = new TicketMetaView();
        view.setChannelCode(channel.getChannelCode());
        view.setChannelName(channel.getChannelName());
        // 表单规则与提交校验读同一份参数，手机端显示的「必填」和服务端拦的不会是两套
        TicketFormRules rules = loadFormRules();
        // 联系电话必填 = 渠道自己勾选 OR 全局参数要求；渠道只会比全局更严，不会放松
        view.setNeedPhone(rules.isContactPhoneRequired()
                || (channel.getNeedPhone() != null && channel.getNeedPhone() == 1));
        view.setMaxImages(rules.getMaxImages());
        view.setFormRules(rules);
        view.setBizDeptName(deptName(channel.getBizDeptId()));

        List<TicketMetaView.CategoryOption> options = new ArrayList<>();
        for (WorkCategory c : categoryMapper.selectAllEnabled(channel.getDeptId())) {
            options.add(new TicketMetaView.CategoryOption(c.getId(), c.getCategoryName()));
        }
        view.setCategories(options);
        // 紧急程度从 sys_config 动态读取，管理员可在「参数配置」页面调整级别和响应时限
        List<UrgencyConfig> urgCfg = loadUrgencyOptions();
        List<TicketMetaView.UrgencyOption> urgOpts = new ArrayList<>();
        for (UrgencyConfig uc : urgCfg) {
            urgOpts.add(new TicketMetaView.UrgencyOption(uc.code, uc.label, uc.slaMinutes));
        }
        view.setUrgencies(urgOpts);
        return view;
    }

    /**
     * 报修人提交问题。
     *
     * @return 单号 + 报修人自设的查询密码；命中蜜罐时返回「看起来成功」的假结果
     */
    @Transactional(rollbackFor = Exception.class)
    public SubmitResult submit(PublicSubmitRequest req, String ip, String userAgent) {
        // 1. 蜜罐：有值即机器人。不报错、不落库，返回形状一致的假单号和假密码，避免给对方探测反馈
        if (StringUtils.hasText(req.getWebsite())) {
            log.warn("命中蜜罐字段，疑似脚本提交: ip={}", ip);
            return SubmitResult.fake(noGenerator.fakeNo(), TicketQueryCode.randomDigits());
        }

        // 2. 填表凭证：令牌里记录的渠道才是本次提交的渠道，客户端传来的任何渠道信息都不作数
        Long channelId = formTokenService.require(req.getFormToken());
        TicketChannelEntity channel = channelService.requireEnabledById(channelId);

        // 2.1 必填项全部来自「参数配置 → 登记表单」：请求体注解只留硬上限，
        //     「要不要填、最少几个字」每次按当前参数现算，管理员改完立刻生效，不用重启也不用发版
        TicketFormRules rules = loadFormRules();

        String title = requireByRules(rules.isTitleRequired(), rules.getTitleMinLen(),
                TicketFormRules.TITLE_MAX, "问题标题", req.getTitle());
        String content = requireByRules(rules.isContentRequired(), rules.getContentMinLen(),
                TicketFormRules.CONTENT_MAX, "问题描述", req.getContent());
        // 这两列在库里都是 NOT NULL，做成选填不等于可以写 null：
        // 标题与描述只要有一个说清问题就够，缺的那列用另一列兜底（列表页、受理台、转工作记录都取这两列）
        if (title.isEmpty() && content.isEmpty()) {
            throw new BizException(40001, rules.problemTextOptional()
                    ? "请至少填写问题标题或问题描述"
                    : (rules.isTitleRequired() ? "请填写问题标题" : "请填写问题描述"));
        }
        if (title.isEmpty()) {
            title = abbreviate(content, 60);
        }
        if (content.isEmpty()) {
            content = title;
        }
        String contactName = requireByRules(rules.isContactNameRequired(), 0,
                TicketFormRules.NAME_MAX, "你的姓名或工号", req.getContactName());
        String location = requireByRules(rules.isLocationRequired(), 0,
                TicketFormRules.LOCATION_MAX, "发生地点", req.getLocation());

        String contactPhone = normalizeContactPhone(req.getContactPhone());
        // 渠道勾选与全局参数取并集：任一处要求必填，这个入口就必须填
        boolean phoneRequired = rules.isContactPhoneRequired()
                || (channel.getNeedPhone() != null && channel.getNeedPhone() == 1);
        if (phoneRequired && contactPhone.isEmpty()) {
            throw new BizException(40001, "请填写联系电话");
        }
        if (!contactPhone.isEmpty() && (contactPhone.length() < 3 || contactPhone.length() > 20)) {
            throw new BizException(40001, "请填写有效联系电话（手机、短号或内线）");
        }
        req.setContactPhone(contactPhone.isEmpty() ? null : contactPhone);

        // 2.1 查询密码：报修人自己设的 6 位数字。
        //     单号已经改成可预测的短流水，这一项就是唯一还在挡遍历的凭证，
        //     所以必填、且规则写死在 TicketQueryCode 里，不做成 sys_config 参数——
        //     可配置的代价是管理员能把它留空或统一，那等于把查询接口公开。
        String queryCode = TicketQueryCode.normalize(req.getQueryCode());
        if (!TicketQueryCode.isFormatValid(queryCode)) {
            throw new BizException(40001, "请设置 6 位数字查询密码");
        }
        if (TicketQueryCode.isWeak(queryCode)) {
            throw new BizException(40001, "查询密码太好猜（全相同、连号都不行），请换一个");
        }

        // 3. 频率限制：内存窗口 + 渠道日配额（DB 兜底，重启不丢）
        if (!rateLimiter.acquire("submit:ip:" + ip, ipLimitCountNow(), ipLimitWindowNow())) {
            throw new BizException(42901, "提交过于频繁，请稍后再试");
        }
        if (!rateLimiter.acquire("submit:ch:" + channel.getId(), channelQpmNow(), 60)) {
            throw new BizException(42902, "当前登记入口提交过于集中，请稍后再试");
        }
        if (StringUtils.hasText(contactPhone)
                && !rateLimiter.acquire("submit:ph:" + TicketTokens.sha256Hex(contactPhone).substring(0, 16),
                phoneHourNow(), 3600)) {
            throw new BizException(42903, "该联系电话提交过于频繁，请联系科室管理员");
        }
        int dailyLimit = channel.getDailyLimit() == null ? 200 : channel.getDailyLimit();
        if (dailyLimit > 0 && ticketMapper.countTodayByChannel(channel.getId()) >= dailyLimit) {
            throw new BizException(42904, "今日该登记入口已达提交上限，请电话联系科室");
        }

        // 4. 问题类型必须属于本渠道科室。工作记录的 category_id 非空，这里必须解析出一个真实分类
        List<WorkCategory> categoryOptions = categoryMapper.selectAllEnabled(channel.getDeptId());
        // 「必须选类型」只在科室确实配了可选类型时才生效，否则会把整个入口的报修全堵死
        if (rules.isCategoryRequired() && req.getCategoryId() == null && !categoryOptions.isEmpty()) {
            throw new BizException(40001, "请选择问题类型");
        }
        Long categoryId = TicketCategoryResolver.resolve(
                req.getCategoryId(),
                channel.getDefaultCategoryId(),
                categoryOptions);

        // 6. 图片 key 必须落在本渠道目录下，否则等于借别人的工单读别人的附件
        List<String> keys = normalizeImageKeys(req.getImageKeys(), channel.getChannelCode(),
                rules.getMaxImages());
        if (keys.size() < rules.getMinImages()) {
            throw new BizException(40001, "请至少上传 " + rules.getMinImages() + " 张现场照片");
        }

        // 7. 落库：单号由「自增 ID + 基数」算出，所以先插入拿到 ID，再回填 ticket_no
        ServiceTicketEntity entity = new ServiceTicketEntity();
        entity.setChannelId(channel.getId());
        entity.setDeptId(channel.getDeptId());
        entity.setBizDeptId(channel.getBizDeptId());
        entity.setCategoryId(categoryId);
        entity.setStatus(TicketStatus.PENDING.code());
        entity.setUrgency(normalizeUrgency(req.getUrgency()));
        entity.setTitle(title);
        entity.setContent(content);
        entity.setLocation(trimToNull(location));
        entity.setContactName(trimToNull(contactName));
        entity.setContactPhone(trimToNull(req.getContactPhone()));
        entity.setImageUrls(writeJson(keys));
        entity.setAccessTokenHash(TicketQueryCode.digest(queryCode));
        entity.setSubmitIp(ip);
        entity.setSubmitUa(abbreviate(userAgent, 255));
        entity.setDueTime(LocalDateTime.now().plusMinutes(slaMinutes(entity.getUrgency())));

        // ticket_no 这一列允许为空就是为这一步服务的：自增 ID 唯一，所以短流水号不可能撞号，
        // 原来那段「撞唯一键就重试三轮」的逻辑随之删掉——并发提交不再会让报修人白填一遍表。
        entity.setTicketNo(null);
        ticketMapper.insert(entity);
        String ticketNo = noGenerator.format(entity.getId());
        if (ticketMapper.updateTicketNo(entity.getId(), ticketNo) != 1) {
            throw new BizException(50001, "提交失败，请稍后重试");
        }
        entity.setTicketNo(ticketNo);

        // 提交成功后立即作废填表凭证：同一令牌不能二次提交，防止重放与误双击
        formTokenService.consume(req.getFormToken());

        writeLog(entity.getId(), 0, null, firstNonBlank(entity.getContactName(), "报修人"),
                "SUBMIT", "提交问题登记", entity.getImageUrls(), 1);

        return SubmitResult.real(ticketNo, queryCode);
    }

    /**
     * 校验「单号 + 查询密码」，返回工单；库里只存摘要，所以拿对方填的算一次比对一次。
     *
     * <p>单号是可预测的短流水，这道校验必须做到「猜不动」，三条约束缺一不可：
     * 1) 先查锁定再查库，被锁时连「这个号到底存不存在」都不透露；
     * 2) 单号不存在与密码错误合并计数、返回同一句文案，
     *    否则攻击者只要观察哪些号会被锁，就白得一个单号存在性探针，前面的努力全废；
     * 3) 单号维度挡针对性爆破，IP 维度挡「每个号试两下」的横扫。
     *
     * @param accessCode 报修人自设的 6 位查询密码；存量老工单填当初那串长令牌同样验得过，
     *                   摘要算法没变，归一化只多了「去掉空白」这一步
     * @param ip         来访 IP，仅用于失败计数
     */
    public ServiceTicketEntity requireVisitorAccess(String ticketNo, String accessCode, String ip) {
        String no = ticketNo == null ? "" : ticketNo.trim().toUpperCase();
        String noKey = "query:no:" + no;
        String ipKey = "query:ip:" + (StringUtils.hasText(ip) ? ip : "unknown");
        if (rateLimiter.blocked(noKey, queryFailPerNo, queryLockWindow)
                || rateLimiter.blocked(ipKey, queryFailPerIp, queryLockWindow)) {
            throw new BizException(42905, "查询失败次数过多，请稍后再试");
        }
        ServiceTicketEntity ticket = StringUtils.hasText(no) ? ticketMapper.selectByNo(no) : null;
        if (ticket == null || !TicketQueryCode.matches(ticket.getAccessTokenHash(), accessCode)) {
            rateLimiter.acquire(noKey, queryFailPerNo, queryLockWindow);
            rateLimiter.acquire(ipKey, queryFailPerIp, queryLockWindow);
            throw new BizException(40301, "单号或查询密码不正确");
        }
        return ticket;
    }

    /** 报修人查看进度：只返回对其可见的流转记录 */
    public TicketPublicView publicDetail(ServiceTicketEntity ticket) {
        TicketPublicView view = TicketPublicView.of(ticket, null);
        TicketChannelEntity viewChannel = channelMapper.selectById(ticket.getChannelId());
        if (viewChannel != null) {
            view.setChannelCode(viewChannel.getChannelCode());
            view.setBizDeptName(deptName(viewChannel.getBizDeptId() != null
                    ? viewChannel.getBizDeptId() : ticket.getBizDeptId()));
        } else {
            view.setBizDeptName(deptName(ticket.getBizDeptId()));
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
        // 告诉报修人「不点确认会怎样」：待确认阶段把系统会动手的时刻和剩余分钟一起下发
        LocalDateTime autoConfirmAt = autoConfirmDeadline(ticket);
        if (autoConfirmAt != null) {
            view.setAutoConfirmAt(autoConfirmAt);
            view.setAutoConfirmRemainMinutes(remainMinutes(autoConfirmAt));
        }
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

    /** 报修人亲手确认完成：20 -> 30；超时没点的由 autoConfirmExpiredTickets 代劳 */
    public void reporterConfirm(ServiceTicketEntity ticket) {
        cas(ticket.getId(), List.of(TicketStatus.WAIT_CONFIRM.code()), TicketStatus.DONE.code(),
                null, null, null, null, LocalDateTime.now());
        writeLog(ticket.getId(), 0, null, "报修人", "CONFIRM", "报修人确认已解决", null, 1);
    }

    /**
     * 报修人超时未确认，由系统代为确认：20 -> 30。
     *
     * <p>开关与时限都来自「参数配置 → 工单流转」，改完下一轮生效，不用重启也不用发版。
     *
     * <p>为什么逐条 CAS 而不是一条 UPDATE ... WHERE status=20 批量改：扫描与写库之间报修人
     * 可能刚好点了「还没解决」，批量语句会把这次人工操作静默吃掉。CAS 影响行数为 0 就跳过，
     * 让人的操作永远压过系统的默认值；代价是多几条语句，而这轮最多也就 AUTO_CONFIRM_BATCH 条。
     *
     * <p>日志写 operator_type=2（系统）而不是冒充处理人，两端的时间线都要能看出这是自动发生的。
     *
     * @return 本轮实际自动确认的条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int autoConfirmExpiredTickets() {
        TicketFlowRules rules = loadFlowRules();
        if (!rules.isAutoConfirmEnabled()) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusHours(rules.getAutoConfirmHours());
        List<Long> ids = ticketMapper.selectConfirmTimeoutIds(deadline, AUTO_CONFIRM_BATCH);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int changed = 0;
        for (Long id : ids) {
            // 与 reporterConfirm 同参：自动确认只是「确认」的另一种触发方式，不该改出另一套列值
            int updated = ticketMapper.updateStatusCas(id, List.of(TicketStatus.WAIT_CONFIRM.code()),
                    TicketStatus.DONE.code(), null, null, null, null, LocalDateTime.now());
            if (updated == 0) {
                continue;
            }
            writeLog(id, 2, null, "系统", "AUTO_CONFIRM",
                    "标记完成后超过 " + rules.getAutoConfirmHours() + " 小时未确认，系统自动确认已解决",
                    null, 1);
            changed++;
        }
        if (changed > 0) {
            log.info("工单自动确认：候选 {} 条，实际确认 {} 条，时限 {} 小时", ids.size(), changed, rules.getAutoConfirmHours());
        }
        return changed;
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
        Long categoryId = TicketCategoryResolver.resolveForAccept(
                ticket.getCategoryId(),
                channel == null ? null : channel.getDefaultCategoryId(),
                categoryMapper.selectAllEnabled(ticket.getDeptId()));

        WorkRecord record = new WorkRecord();
        record.setUserId(cu.getId());
        record.setDeptId(cu.getDeptId() == null ? 0L : cu.getDeptId());
        // 业务科室 = 渠道绑定的问题所在科室；受理科室仍是信息科（record.deptId）
        record.setBizDeptId(ticket.getBizDeptId());
        record.setCategoryId(categoryId);
        record.setStatusId(1);
        record.setTitle(abbreviate("[#" + ticket.getTicketNo() + "] " + ticket.getTitle(), 200));
        record.setContent(buildRecordContent(ticket));
        record.setImageUrls(ticket.getImageUrls());
        record.setStartTime(ticket.getCreateTime() == null ? LocalDateTime.now() : ticket.getCreateTime());
        record.setEndTime(ticket.getDueTime() == null
                ? LocalDateTime.now().plusMinutes(slaMinutes(ticket.getUrgency()))
                : ticket.getDueTime());
        // 紧急工单在待办列表里要能被标红，沿用工作记录既有的 is_important 语义
        // 紧急度 code 最小的 = 最高优先级，标记为重要
        List<UrgencyConfig> urgCfg = loadUrgencyOptions();
        int highestCode = urgCfg.isEmpty() ? 1 : urgCfg.get(0).code;
        record.setIsImportant(nz(ticket.getUrgency()) == highestCode ? 1 : 0);
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
        String bizName = deptName(ticket.getBizDeptId());
        if (StringUtils.hasText(bizName)) {
            sb.append("问题科室：").append(bizName).append('\n');
        }
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
        view.setBizDeptName(deptName(e.getBizDeptId()));
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
        // 紧急程度显示名从 sys_config 动态获取
        for (UrgencyConfig uc : loadUrgencyOptions()) {
            if (uc.code == nz(e.getUrgency())) {
                view.setUrgencyName(uc.label);
                break;
            }
        }
        if (e.getDueTime() != null) {
            boolean settled = TicketStatus.of(e.getStatus()) != null && TicketStatus.of(e.getStatus()).isFinal();
            LocalDateTime to = settled && e.getCloseTime() != null ? e.getCloseTime() : LocalDateTime.now();
            view.setSlaRemainMinutes(Duration.between(to, e.getDueTime()).toMinutes());
        }
        // 「待报修人确认」且自动确认开着时下发倒计时，受理台才能解释这条单子会自己变已完成
        LocalDateTime autoConfirmAt = autoConfirmDeadline(e);
        if (autoConfirmAt != null) {
            view.setAutoConfirmAt(autoConfirmAt);
            view.setAutoConfirmRemainMinutes(remainMinutes(autoConfirmAt));
        }
        return view;
    }

    /**
     * 回复/补充附件用的重载：沿用硬上限。
     *
     * <p>「参数配置 → 登记表单」管的是报修人**登记时**要填什么，
     * 不该顺带约束员工回复和报修人补充说明时能带几张图，所以这两条链路不进参数化分支。
     */
    private List<String> normalizeImageKeys(List<String> keys, String channelCode) {
        return normalizeImageKeys(keys, channelCode, MAX_IMAGES);
    }

    private List<String> normalizeImageKeys(List<String> keys, String channelCode, int maxImages) {
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
            if (result.size() > maxImages) {
                throw new BizException(40001, "最多上传 " + maxImages + " 张图片");
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

    /**
     * 紧急度 -> SLA 分钟数。
     * 从 sys_config urgency 分组动态读取；配置缺失时回退到硬编码默认值。
     */
    int slaMinutes(Integer urgency) {
        int code = nz(urgency);
        for (UrgencyConfig uc : loadUrgencyOptions()) {
            if (uc.code == code) return uc.slaMinutes;
        }
        // 兜底：未知 code 按 24 小时
        return 1440;
    }

    /**
     * 校验前端传来的 urgency code 是否在配置范围内；
     * 不合法时取中间档作为默认值（配置为空时回退到 code=2）。
     */
    private Integer normalizeUrgency(Integer urgency) {
        List<UrgencyConfig> opts = loadUrgencyOptions();
        if (opts.isEmpty()) {
            int u = nz(urgency);
            return (u >= 1 && u <= 4) ? u : 2;
        }
        int code = nz(urgency);
        for (UrgencyConfig uc : opts) {
            if (uc.code == code) return code;
        }
        // 不合法：取中间档
        return opts.get(opts.size() / 2).code;
    }

    /**
     * 读取手机端表单规则（sys_config 的 ticket_form 分组）。
     *
     * <p>参数被删空、值写错、甚至这张表临时查不到，都退回与既有行为一致的默认值：
     * 「参数配置页改坏了」不该演变成「全院报修都提交不了」。
     */
    TicketFormRules loadFormRules() {
        try {
            return TicketFormRules.fromRows(sysConfigMapper.selectByGroup(TicketFormRules.CONFIG_GROUP));
        } catch (Exception ex) {
            log.warn("加载登记表单规则失败，使用默认值", ex);
            return TicketFormRules.defaults();
        }
    }

    /**
     * 读取工单流转参数（sys_config 的 ticket_flow 分组），带 {@link #FLOW_RULES_CACHE_MS} 短时缓存。
     *
     * <p>同样不能因为参数缺失或查库异常就抛出去：这条方法在列表渲染里被调用，
     * 「参数配置页被改坏了」不该变成「受理台打不开」。
     */
    TicketFlowRules loadFlowRules() {
        TicketFlowRules cached = cachedFlowRules;
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedFlowRulesAt < FLOW_RULES_CACHE_MS) {
            return cached;
        }
        TicketFlowRules rules;
        try {
            rules = TicketFlowRules.fromRows(sysConfigMapper.selectByGroup(TicketFlowRules.CONFIG_GROUP));
        } catch (Exception ex) {
            log.warn("加载工单流转参数失败，使用默认值", ex);
            rules = TicketFlowRules.defaults();
        }
        cachedFlowRules = rules;
        cachedFlowRulesAt = now;
        return rules;
    }

    /**
     * 这条工单会被系统自动确认的时刻；状态不是「待报修人确认」或参数已关闭时返回 null。
     *
     * <p>计时起点是标记完成的时间（done_time），和「维修人员确认维修完成」这句话对齐；
     * 历史脏数据里 done_time 可能为空，退到 update_time——宁可提示一个近似值，
     * 也不要在列表渲染里冒出 null 指针。
     */
    private LocalDateTime autoConfirmDeadline(ServiceTicketEntity e) {
        if (TicketStatus.WAIT_CONFIRM.code() != nz(e.getStatus())) {
            return null;
        }
        Integer hours = loadFlowRules().effectiveAutoConfirmHours();
        if (hours == null) {
            return null;
        }
        LocalDateTime doneAt = e.getDoneTime() != null ? e.getDoneTime() : e.getUpdateTime();
        return doneAt == null ? null : doneAt.plusHours(hours);
    }

    /** 距离自动确认还剩几分钟；下限夹到 0，别让页面显示「剩 -12 分钟」 */
    private static long remainMinutes(LocalDateTime deadline) {
        return Math.max(0L, Duration.between(LocalDateTime.now(), deadline).toMinutes());
    }

    /**
     * 按参数规则校验一个文本字段，返回 trim 后的值（可能为空串，由调用方决定怎么兜底）。
     *
     * <p>语义是「必填则不可空；填了就必须够字数、且不超列宽」，
     * 所以把参数改成选填之后，旧前端传来的空值也不会撞数据库非空约束。
     */
    private static String requireByRules(boolean required, int minLen, int maxLen, String label, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (required && value.isEmpty()) {
            throw new BizException(40001, "请填写" + label);
        }
        if (value.length() > maxLen) {
            throw new BizException(40001, label + "不能超过 " + maxLen + " 字");
        }
        if (!value.isEmpty() && value.length() < minLen) {
            throw new BizException(40001, minLen > 1
                    ? label + "至少 " + minLen + " 个字"
                    : "请填写" + label);
        }
        return value;
    }

    /**
     * 从 sys_config 读取紧急程度配置列表。
     * sort_order 作为 urgency code，config_value 作为 SLA 分钟数，config_label 作为显示名。
     */
    private List<UrgencyConfig> loadUrgencyOptions() {
        try {
            List<SysConfigEntity> list = sysConfigMapper.selectByGroup("urgency");
            if (list == null || list.isEmpty()) return List.of();
            List<UrgencyConfig> result = new ArrayList<>();
            for (SysConfigEntity e : list) {
                int code = e.getSortOrder() != null ? e.getSortOrder() : 0;
                int slaMin = 1440;
                try { slaMin = Integer.parseInt(e.getConfigValue()); } catch (Exception ignored) {}
                String label = e.getConfigLabel() != null ? e.getConfigLabel() : e.getConfigKey();
                result.add(new UrgencyConfig(code, label, slaMin));
            }
            result.sort((a, b) -> Integer.compare(a.code, b.code));
            return result;
        } catch (Exception ex) {
            log.warn("加载紧急程度配置失败，使用硬编码默认值", ex);
            return List.of();
        }
    }

    /** 紧急程度的运行时配置：code（即 urgency 字段值）、显示名、SLA 分钟数 */
    private record UrgencyConfig(int code, String label, int slaMinutes) {}

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private String deptName(Long deptId) {
        if (deptId == null) {
            return null;
        }
        DeptEntity dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
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

    /** 只保留数字；+86 且共 13 位时去掉国家码。短号/内线原样保留。 */
    static String normalizeContactPhone(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\\D", "");
        if (s.startsWith("86") && s.length() == 13) {
            s = s.substring(2);
        }
        return s;
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
        /** 查询密码：报修人自设的 6 位数字（历史字段名叫 accessToken，语义已变，接口仍同名回传一份便于旧前端过渡） */
        private final String queryCode;
        private final boolean fake;

        private SubmitResult(String ticketNo, String queryCode, boolean fake) {
            this.ticketNo = ticketNo;
            this.queryCode = queryCode;
            this.fake = fake;
        }

        static SubmitResult real(String ticketNo, String queryCode) {
            return new SubmitResult(ticketNo, queryCode, false);
        }

        static SubmitResult fake(String ticketNo, String queryCode) {
            return new SubmitResult(ticketNo, queryCode, true);
        }

        public String getTicketNo() {
            return ticketNo;
        }

        public String getQueryCode() {
            return queryCode;
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
