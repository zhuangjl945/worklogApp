package com.zjl.worklog.ticket;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 免登录接口的内存限流。
 *
 * <p>刻意不引入 Redis：这是单机部署的科室内部系统，多一个中间件就多一套运维。
 * 代价是进程重启后计数清零，所以渠道「当日配额」另有 service_ticket 表的 DB 统计兜底。
 * 将来横向扩容时把本类换成 Redis 实现即可，调用方只依赖 acquire 一个方法。
 */
@Component
public class TicketRateLimiter {

    /** 维度键 -> 最近命中时间戳（毫秒）队列，构成一个滑动窗口 */
    private final ConcurrentHashMap<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    private volatile long lastPurgeAt = System.currentTimeMillis();

    private static final int MAX_KEYS = 20000;
    private static final long PURGE_INTERVAL_MS = 60_000L;

    /**
     * 申请配额：窗口内已用次数未达上限则放行。
     *
     * @param key           维度键，例如 submit:ip:10.1.2.3
     * @param maxCount      窗口内允许次数
     * @param windowSeconds 窗口长度（秒）
     */
    public boolean acquire(String key, int maxCount, long windowSeconds) {
        purgeIfDue();
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1L, windowSeconds) * 1000L;

        ArrayDeque<Long> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && now - hits.peekFirst() > windowMs) {
                hits.pollFirst();
            }
            if (hits.size() >= maxCount) {
                return false;
            }
            hits.addLast(now);
        }
        return true;
    }

    /** 清理长期不活跃的窗口，防止被恶意刷 key 把内存堆满 */
    private void purgeIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastPurgeAt < PURGE_INTERVAL_MS && windows.size() < MAX_KEYS) {
            return;
        }
        lastPurgeAt = now;
        for (Map.Entry<String, ArrayDeque<Long>> e : windows.entrySet()) {
            ArrayDeque<Long> q = e.getValue();
            synchronized (q) {
                boolean stale = q.isEmpty() || now - q.peekLast() > 3600_000L;
                if (stale) {
                    windows.remove(e.getKey(), q);
                }
            }
        }
    }
}
