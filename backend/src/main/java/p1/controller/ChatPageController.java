package p1.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Web 前端入口控制器。
 * <p>
 * 打包后的 Vue 静态资源放在 {@code static/chat-ui} 下，这里负责把常见入口
 * 都转发到同一个前端页面，避免用户打开根路径或旧入口时看到 Spring 的错误页。
 */
@Controller
public class ChatPageController {

    /**
     * 打开 Web 聊天页面。
     * <p>
     * 根路径用于双击启动后的默认访问；/api/chat 保留旧入口；/chat-ui 是发布包中的显式前端入口。
     */
    @GetMapping({"/", "/index.html", "/api/chat", "/api/chat/", "/chat-ui", "/chat-ui/"})
    public String chatPage() {
        return "forward:/chat-ui/index.html";
    }

    /**
     * 兼容旧版 Vite 构建产物中的根路径资源引用。
     * <p>
     * 旧 index.html 可能引用 /assets/index.js，而发布包实际资源位于 /chat-ui/assets/index.js。
     */
    @GetMapping("/assets/**")
    public String legacyAssets(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();

        // 去掉可能存在的应用上下文路径，确保 forward 目标始终是应用内绝对路径。
        if (contextPath != null && !contextPath.isBlank() && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }

        return "forward:/chat-ui" + requestPath;
    }

    /**
     * 兼容浏览器默认请求的 favicon。
     */
    @GetMapping("/favicon.ico")
    public String favicon() {
        return "forward:/chat-ui/favicon.ico";
    }
}
