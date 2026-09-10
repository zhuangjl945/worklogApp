package com.zjl.worklog.config;

import com.zjl.worklog.config.entity.SysConfigEntity;
import com.zjl.worklog.config.mapper.SysConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统参数的运行期读取入口。
 *
 * <p>为什么要有这个类：sys_config 里真正会作用到运行期行为的参数（限流阈值、直传大小、登录有效期）
 * 被提交、限流、上传签名这些高频路径读取，绝不能每条请求查一次库；这里做一层
 * 「整表快照 + 短 TTL」的缓存，保存方调 {@link #invalidate()}，最迟下一轮生效，不用重启不用发版。
 *
 * <p>读不到、表临时查不动、值写错，一律回落到调用方传的默认值：
 * 「参数页被改坏」不该演变成「提交通道被限流掐死」或「没人能登录」。
 * 默认值由调用方决定，通常是 application.yml 里那份——yml 是兜底，不是唯一来源。
 */
@Service
public class SysConfigService {

    /** 系统基础 */
    public static final String GROUP_SYSTEM = "system";
    /** 文件上传 */
    public static final String GROUP_UPLOAD = "upload";
    /** 限流防护 */
    public static final String GROUP_RATE_LIMIT = "rate_limit";

    /** 登录有效期（秒） */
    public static final String KEY_JWT_EXPIRE_SECONDS = "jwt_expire_seconds";
    /** OSS 直传单文件大小上限（MB） */
    public static final String KEY_MAX_FILE_SIZE_MB = "max_file_size_mb";
    /** 单 IP 在窗口内允许的提交次数 */
    public static final String KEY_IP_COUNT = "ip_count";
    /** 单 IP 限流窗口长度（秒） */
    public static final String KEY_IP_WINDOW_SECONDS = "ip_window_seconds";
    /** 单渠道每分钟提交上限 */
    public static final String KEY_CHANNEL_QPM = "channel_qpm";
    /** 同一手机号每小时上限 */
    public static final String KEY_PHONE_HOUR = "phone_hour";

    /** 快照有效期：30 秒。对以分钟/小时计的限流与时长参数，这个滞后可以忽略 */
    private static final long TTL_MS = 30_000L;

    private static final Logger log = LoggerFactory.getLogger(SysConfigService.class);

    private final SysConfigMapper sysConfigMapper;

    /** "group|key" -> 原始字符串值；整体替换，不做原地增删，读方永远看到完整快照 */
    private volatile Map<String, String> snapshot = Map.of();
    private volatile long snapshotAt;

    public SysConfigService(SysConfigMapper sysConfigMapper) {
        this.sysConfigMapper = sysConfigMapper;
    }

    /** 参数保存后由写入方调用，让下一轮读取立刻拿到新值 */
    public void invalidate() {
        snapshotAt = 0L;
    }

    /** 原始字符串值；缺失或读取异常返回 fallback */
    /** 直传单文件上限的兜底值（MB），与改造前 OssController 里硬编码的 10MB 一致 */
    private static final int DEFAULT_MAX_FILE_MB = 10;
    /** 直传单文件上限的硬天花板（MB）：参数页休想突破，超过它的值一律按它执行 */
    private static final int HARD_CAP_MAX_FILE_MB = 50;

    /**
     * 登录用户 OSS 直传的单文件大小上限（MB）。
     *
     * <p>取「参数配置 → 文件上传 → 单文件上限」，读不到按 10MB，最高只到 50MB。
     * 注意手机端访客直传走的是 OssPolicyService 里另一套更严的图片专用上限（5MB），
     * 那个值刻意不跟这里联动：访客能传多大和员工能传多大是两件事。
     */
    public int maxUploadFileSizeMb() {
        int mb = getInt(GROUP_UPLOAD, KEY_MAX_FILE_SIZE_MB, DEFAULT_MAX_FILE_MB);
        return Math.max(1, Math.min(HARD_CAP_MAX_FILE_MB, mb));
    }

    /** 同上，换算成字节，给 policy 的 content-length-range 用 */
    public long maxUploadBytes() {
        return maxUploadFileSizeMb() * 1024L * 1024L;
    }

    public String getString(String group, String key, String fallback) {
        String v = snapshot().get(group + "|" + key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    /** 整数值；非数字/空值回落到 fallback，顺手容忍参数页偶发写入的「8.0」这类写法 */
    public int getInt(String group, String key, int fallback) {
        String v = getString(group, key, null);
        if (v == null) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            log.warn("参数 {}.{} 不是合法数字（{}），按默认值 {} 处理", group, key, v, fallback);
            return fallback;
        }
    }

    /** 长整型值，语义同 {@link #getInt} */
    public long getLong(String group, String key, long fallback) {
        String v = getString(group, key, null);
        if (v == null) {
            return fallback;
        }
        try {
            return (long) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            log.warn("参数 {}.{} 不是合法数字（{}），按默认值 {} 处理", group, key, v, fallback);
            return fallback;
        }
    }

    /** 布尔值：容忍 1/0、true/false、on/off、yes/no，其他一律按 fallback */
    public boolean getBoolean(String group, String key, boolean fallback) {
        String v = getString(group, key, null);
        if (v == null) {
            return fallback;
        }
        switch (v.toLowerCase()) {
            case "1":
            case "true":
            case "yes":
            case "on":
            case "enabled":
                return true;
            case "0":
            case "false":
            case "no":
            case "off":
            case "disabled":
                return false;
            default:
                return fallback;
        }
    }

    private Map<String, String> snapshot() {
        long now = System.currentTimeMillis();
        if (now - snapshotAt < TTL_MS) {
            return snapshot;
        }
        Map<String, String> next = new LinkedHashMap<>();
        try {
            for (SysConfigEntity e : sysConfigMapper.selectAll()) {
                if (e.getConfigGroup() != null && e.getConfigKey() != null) {
                    next.put(e.getConfigGroup() + "|" + e.getConfigKey(), e.getConfigValue());
                }
            }
        } catch (Exception ex) {
            // 查库失败不能让业务停摆：沿用上一份快照（可能为空），并把它当作「本轮不再生效」重试
            log.warn("读取系统参数失败，本轮沿用上一份快照：{}", ex.getMessage());
            snapshotAt = now - TTL_MS / 2;
            return snapshot;
        }
        snapshot = Map.copyOf(next);
        snapshotAt = now;
        return snapshot;
    }
}
