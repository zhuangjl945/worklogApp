package com.zjl.worklog.security;

import org.springframework.stereotype.Controller;
import com.zjl.worklog.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 前端单页应用（SPA）路由兜底。
 *
 * <p>Vue Router 使用 history 模式，用户直接访问 /work-records、/m/ABC123 这类前端路径时，
 * 后端并没有对应的接口，需要统一转发到 index.html 交给前端路由处理。
 * 这里的正则 [^\.]* 表示「路径段里不含点号」，因此不会劫持 /assets/xxx.js 等静态资源请求。
 *
 * <p>注意：新增一级前端路径（例如手机端 /m/**）时无需改这里，但要确认
 * SecurityConfig 的放行列表覆盖到它，否则兜底会被 401 拦截。
 */
@Controller
public class SpaFallbackController {

    /**
     * 转发所有不含点号的前端路径到 index.html。
     * 覆盖：/ 、/login 、/work-records 、/contract/detail/1 、/m/CHANNEL 、/m/ticket/ST2026...
     *
     * <p>/api/** 必须在这里单独挡下来：上面那两条正则本来就吃得下 /api/xxx，
     * 一旦让拼错的接口路径走到 forward，拿回来的就是 index.html 的内容或一句
     * 「No static resource」式的系统异常，而不是能看懂的 404——排查时最容易把人带偏。
     * 自前端产物改由 nginx 直接发布之后，后端不再持有 dist，这个坑就成了常态。
     */
    @RequestMapping({"/", "/login", "/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String forwardSpa(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && (uri.equals("/api") || uri.startsWith("/api/"))) {
            throw new BizException(40404, "接口不存在");
        }
        return "forward:/index.html";
    }
}
