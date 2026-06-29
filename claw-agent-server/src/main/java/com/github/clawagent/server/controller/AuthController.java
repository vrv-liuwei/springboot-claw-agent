package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.server.dto.ApiTokenCreateRequest;
import com.github.clawagent.server.dto.ApiTokenCreateResponse;
import com.github.clawagent.server.dto.ApiTokenView;
import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.spi.AgentEventStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AuthController 提供本地主服务的轻量认证配置入口。
 * 当前只管理 API Token 生命周期，不启用全局鉴权拦截，避免影响本地 WebUI。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final ApiTokenService apiTokenService;
    private final AgentEventStore eventStore;

    public AuthController(ApiTokenService apiTokenService,
                          @Qualifier("agentEventStore") AgentEventStore eventStore) {
        this.apiTokenService = apiTokenService;
        this.eventStore = eventStore;
    }

    @GetMapping("/tokens")
    public List<ApiTokenView> tokens() {
        return apiTokenService.list();
    }

    @PostMapping("/tokens")
    public ApiTokenCreateResponse createToken(@RequestBody ApiTokenCreateRequest request) {
        ApiTokenCreateResponse response = apiTokenService.create(request);
        recordTokenAudit("auth.token.created", "API Token 已创建", response.token(), response.tokenInfo());
        return response;
    }

    @DeleteMapping("/tokens/{tokenId}")
    public ApiTokenView revokeToken(@PathVariable String tokenId) {
        ApiTokenView view = apiTokenService.revoke(tokenId);
        recordTokenAudit("auth.token.revoked", "API Token 已撤销", null, view);
        return view;
    }

    private void recordTokenAudit(String type, String message, String rawToken, ApiTokenView view) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("tokenId", view.id());
        details.put("name", view.name());
        details.put("status", view.status());
        details.put("tokenPrefix", view.tokenPrefix());
        details.put("metadataCount", String.valueOf(view.metadata() == null ? 0 : view.metadata().size()));
        details.put("tokenReturned", String.valueOf(rawToken != null && !rawToken.isBlank()));
        // 审计只保留 token 前缀和状态，绝不记录 token 明文或 hash。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }
}
