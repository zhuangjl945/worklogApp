package com.zjl.worklog.ticket;

import com.zjl.worklog.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手机端表单令牌：把「一次填表」绑定成一个短期、一次性、绑渠道绑来源IP 的凭证。
 *
 * <p>为什么不用二维码里带 HMAC 签名：一个静态张贴的二维码，它的签名参数只能打印死，
 * 要么永久有效（等于没有时效），要么几分钟后就扫不出来（等于不可用）。
 * 而且手机端一次填表要连调 meta、多次取上传签名、最后 submit，
 * 一次性 nonce 会在第一次调用就被消耗掉，后面全失败。
 *
 * <p>form token 解决的是同一件事，但不需要把秘密印到纸上：
 * 脚本必须先申请 token 才能提交，而申请本身就受「单 IP 限流」约束，
 * 并且一个 token 只能提交一单，无法复用。
 *
 * <p>放在内存是有意的：TTL 只有 30 分钟，重启丢掉最多让正在填表的人重新打开页面。
 * 若将来多实例部署，把这个类换成 Redis 实现即可，调用方只依赖 issue/require/consume。
 */
@Component
public class TicketFormTokenService {

    /** 令牌有效期：够填完一张带拍照的表单，又不至于长期有效 */
    private static final long TTL_MILLIS = 30 * 60 * 1000L;

    private static final class Token {
        final Long channelId;
        final String ip;
        final long expireAt;

        Token(Long channelId, String ip, long expireAt) {
            this.channelId = channelId;
            this.ip = ip;
            this.expireAt = expireAt;
        }
    }

    private final ConcurrentHashMap<String, Token> tokens = new ConcurrentHashMap<>();
    private volatile long lastPurgeAt = System.currentTimeMillis();

    /** 申请一个填表令牌 */
    public String issue(Long channelId, String ip) {
        purgeIfDue();
        String token = TicketTokens.randomToken();
        tokens.put(token, new Token(channelId, ip, System.currentTimeMillis() + TTL_MILLIS));
        return token;
    }

    /** 校验令牌是否仍然有效，返回它绑定的渠道ID；不做消费 */
    public Long require(String token) {
        Token t = token == null ? null : tokens.get(token.trim());
        if (t == null || t.expireAt < System.currentTimeMillis()) {
            throw new BizException(40304, "填表凭证已过期，请重新扫码");
        }
        return t.channelId;
    }

    /** 消费令牌（提交成功路径上调用）：一个令牌只能提交一单 */
    public void consume(String token) {
        if (token != null) {
            tokens.remove(token.trim());
        }
    }

    public long ttlSeconds() {
        return TTL_MILLIS / 1000L;
    }

    private void purgeIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastPurgeAt < 60_000L && tokens.size() < 20000) {
            return;
        }
        lastPurgeAt = now;
        // 用迭代器删除，避免 values().removeIf 在并发下漏删
        for (Map.Entry<String, Token> e : tokens.entrySet()) {
            if (e.getValue().expireAt < now) {
                tokens.remove(e.getKey());
            }
        }
    }
}
