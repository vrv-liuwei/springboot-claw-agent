package com.github.clawagent.server.controller.app;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * App 页面入口。
 * Spring Boot 会托管 /app/index.html 这类静态资源，但不会自动把 /app/ 当成嵌套目录首页。
 */
@Controller
public class AppPageController {

    @GetMapping({"/app", "/app/"})
    public String appIndex() {
        // Electron 和浏览器都访问同一个本地 URL，入口统一转发到构建后的 App 静态页。
        return "forward:/app/index.html";
    }
}
