package com.zjl.worklog.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaFallbackController {

    @RequestMapping({"/", "/login"})
    public String forwardRoot() {
        return "forward:/index.html";
    }

    @RequestMapping({"/{path:^(?!api$)(?!.*\\..*).*$}", "/{path:^(?!api$)(?!.*\\..*).*$}/**"})
    public String forwardOther() {
        return "forward:/index.html";
    }
}
