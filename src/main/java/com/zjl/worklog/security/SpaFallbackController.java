package com.zjl.worklog.security;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class SpaFallbackController {

    @RequestMapping({"/", "/login", "/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String forwardSpa() {
        return "forward:/index.html";
    }
}
