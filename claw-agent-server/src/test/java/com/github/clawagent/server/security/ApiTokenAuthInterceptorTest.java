package com.github.clawagent.server.security;

import com.github.clawagent.server.support.TestIdentityStores;

import com.github.clawagent.server.config.ServerAuthProperties;
import com.github.clawagent.server.dto.ApiTokenCreateRequest;
import com.github.clawagent.server.dto.DevicePairRequest;
import com.github.clawagent.server.dto.DevicePairingCreateRequest;
import com.github.clawagent.server.dto.LocalUserCreateRequest;
import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.server.service.DeviceRegistryService;
import com.github.clawagent.server.service.LocalUserSessionService;
import com.github.clawagent.server.service.LocalUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiTokenAuthInterceptorTest {
    @TempDir
    Path tempDir;

    @Test
    void skipsAuthWhenRequiredSwitchIsOff() throws Exception {
        ApiTokenAuthInterceptor interceptor = newInterceptor(new ServerAuthProperties(), services());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request("GET", "/api/v1/tasks"), response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus());
    }

    @Test
    void acceptsApiTokenWhenAuthIsRequired() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        String token = services.apiTokenService().create(new ApiTokenCreateRequest(
                "CI",
                "user-1",
                "alice",
                "custom",
                List.of("builtin.execute.command"),
                List.of("tasks:write"),
                null,
                Map.of())).token();
        MockHttpServletRequest request = request("POST", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer " + token);

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals(1L, services.apiTokenService().list().get(0).usageCount());
        assertEquals("api-token", request.getAttribute(ApiTokenAuthInterceptor.ATTR_AUTH_TYPE));
        assertEquals("user-1", request.getAttribute(ApiTokenAuthInterceptor.ATTR_USER_ID));
        assertEquals("custom", request.getAttribute(ApiTokenAuthInterceptor.ATTR_TOKEN_PERMISSION_MODE));
        assertEquals("builtin.execute.command", request.getAttribute(ApiTokenAuthInterceptor.ATTR_TOKEN_APPROVED_TOOL_IDS));
    }

    @Test
    void acceptsLegacyApiTokenWithoutScopesForCompatibility() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        String token = services.apiTokenService().create(new ApiTokenCreateRequest("Legacy", Map.of())).token();
        MockHttpServletRequest request = request("POST", "/api/v1/tasks");
        request.addHeader("X-ClawAgent-Token", token);

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void rejectsApiTokenWhenScopeDoesNotMatchRequest() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        String token = services.apiTokenService().create(new ApiTokenCreateRequest(
                "ReadOnly",
                "",
                "",
                "",
                List.of(),
                List.of("tasks:read"),
                null,
                Map.of())).token();
        MockHttpServletRequest request = request("POST", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = newInterceptor(properties, services).preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertEquals("{\"error\":\"insufficient_scope\"}", response.getContentAsString());
    }

    @Test
    void acceptsWildcardDomainScope() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        String token = services.apiTokenService().create(new ApiTokenCreateRequest(
                "TaskAdmin",
                "",
                "",
                "",
                List.of(),
                List.of("tasks:*"),
                null,
                Map.of())).token();
        MockHttpServletRequest request = request("POST", "/api/v1/tasks/123/approvals/step/approve");
        request.addHeader("Authorization", "Bearer " + token);

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void supportsConfigurableScopeMappings() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        properties.setScopeMappings(Map.of("runtime", List.of("/api/v1/config/runtime")));
        String token = services.apiTokenService().create(new ApiTokenCreateRequest(
                "RuntimeRead",
                "",
                "",
                "",
                List.of(),
                List.of("runtime:read"),
                null,
                Map.of())).token();
        MockHttpServletRequest request = request("GET", "/api/v1/config/runtime");
        request.addHeader("Authorization", "Bearer " + token);

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void configurableScopeMappingsOverrideDefaultDomains() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        properties.setScopeMappings(Map.of("runtime", List.of("/api/v1/config/runtime")));
        String token = services.apiTokenService().create(new ApiTokenCreateRequest(
                "ConfigRead",
                "",
                "",
                "",
                List.of(),
                List.of("config:read"),
                null,
                Map.of())).token();
        MockHttpServletRequest request = request("GET", "/api/v1/config/runtime");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = newInterceptor(properties, services).preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertEquals("{\"error\":\"insufficient_scope\"}", response.getContentAsString());
    }

    @Test
    void acceptsLocalUserSessionWhenAuthIsRequired() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        var user = services.localUserService().create(
                new LocalUserCreateRequest("admin", "123456", "管理员", "admin", Map.of()));
        String sessionToken = services.localUserSessionService().create(user).sessionToken();
        MockHttpServletRequest request = request("POST", "/api/v1/tasks");
        request.addHeader("X-ClawAgent-Session", sessionToken);

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals("session", request.getAttribute(ApiTokenAuthInterceptor.ATTR_AUTH_TYPE));
        assertEquals(user.id(), request.getAttribute(ApiTokenAuthInterceptor.ATTR_USER_ID));
        assertEquals("admin", request.getAttribute(ApiTokenAuthInterceptor.ATTR_USER_ROLE));
        assertTrue(services.localUserSessionService().authenticate(sessionToken)
                .orElseThrow()
                .session()
                .lastUsedAt() != null);
    }

    @Test
    void viewerSessionCanReadButCannotSubmitTasks() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        var viewer = services.localUserService().create(
                new LocalUserCreateRequest("viewer", "123456", "只读用户", "viewer", Map.of()));
        String sessionToken = services.localUserSessionService().create(viewer).sessionToken();
        MockHttpServletRequest readRequest = request("GET", "/api/v1/tasks");
        readRequest.addHeader("X-ClawAgent-Session", sessionToken);
        MockHttpServletRequest writeRequest = request("POST", "/api/v1/tasks");
        writeRequest.addHeader("X-ClawAgent-Session", sessionToken);
        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        ApiTokenAuthInterceptor interceptor = newInterceptor(properties, services);

        assertTrue(interceptor.preHandle(readRequest, new MockHttpServletResponse(), new Object()));
        boolean allowed = interceptor.preHandle(writeRequest, writeResponse, new Object());

        assertFalse(allowed);
        assertEquals(403, writeResponse.getStatus());
        assertEquals("{\"error\":\"insufficient_role\"}", writeResponse.getContentAsString());
    }

    @Test
    void operatorSessionCanSubmitTasksButCannotManageAuth() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        var operator = services.localUserService().create(
                new LocalUserCreateRequest("operator", "123456", "执行用户", "operator", Map.of()));
        String sessionToken = services.localUserSessionService().create(operator).sessionToken();
        MockHttpServletRequest taskRequest = request("POST", "/api/v1/tasks");
        taskRequest.addHeader("X-ClawAgent-Session", sessionToken);
        MockHttpServletRequest authRequest = request("POST", "/api/v1/auth/tokens");
        authRequest.addHeader("X-ClawAgent-Session", sessionToken);
        MockHttpServletResponse authResponse = new MockHttpServletResponse();
        ApiTokenAuthInterceptor interceptor = newInterceptor(properties, services);

        assertTrue(interceptor.preHandle(taskRequest, new MockHttpServletResponse(), new Object()));
        boolean allowed = interceptor.preHandle(authRequest, authResponse, new Object());

        assertFalse(allowed);
        assertEquals(403, authResponse.getStatus());
        assertEquals("{\"error\":\"insufficient_role\"}", authResponse.getContentAsString());
    }

    @Test
    void adminSessionCanManageAuthEndpoints() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        var admin = services.localUserService().create(
                new LocalUserCreateRequest("admin", "123456", "管理员", "admin", Map.of()));
        String sessionToken = services.localUserSessionService().create(admin).sessionToken();
        MockHttpServletRequest request = request("POST", "/api/v1/auth/users");
        request.addHeader("X-ClawAgent-Session", sessionToken);

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void anyLocalSessionCanAccessSelfAuthEndpoints() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        var user = services.localUserService().create(
                new LocalUserCreateRequest("viewer", "123456", "只读用户", "viewer", Map.of()));
        String sessionToken = services.localUserSessionService().create(user).sessionToken();
        MockHttpServletRequest request = request("POST", "/api/v1/auth/logout");
        request.addHeader("X-ClawAgent-Session", sessionToken);

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
    }

    @Test
    void acceptsPairedDeviceCredentialForTaskEndpoints() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        var pairing = services.deviceRegistryService().createPairingCode(new DevicePairingCreateRequest(
                "Desktop", "desktop", 300, "custom", List.of("builtin.execute.command"), Map.of()));
        var paired = services.deviceRegistryService().pair(new DevicePairRequest(pairing.code(), Map.of("client", "test")));
        MockHttpServletRequest request = request("POST", "/api/v1/tasks");
        request.addHeader("X-ClawAgent-Device-Id", paired.device().id());
        request.addHeader("X-ClawAgent-Device-Secret", paired.deviceSecret());

        boolean allowed = newInterceptor(properties, services).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals("device", request.getAttribute(ApiTokenAuthInterceptor.ATTR_AUTH_TYPE));
        assertEquals(paired.device().id(), request.getAttribute(ApiTokenAuthInterceptor.ATTR_DEVICE_ID));
        assertEquals("custom", request.getAttribute(ApiTokenAuthInterceptor.ATTR_DEVICE_PERMISSION_MODE));
        assertEquals("builtin.execute.command", request.getAttribute(ApiTokenAuthInterceptor.ATTR_DEVICE_APPROVED_TOOL_IDS));
    }

    @Test
    void rejectsDeviceCredentialForAdminEndpoints() throws Exception {
        TestServices services = services();
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        var pairing = services.deviceRegistryService().createPairingCode(new DevicePairingCreateRequest(
                "Desktop", "desktop", 300, "auto", List.of(), Map.of()));
        var paired = services.deviceRegistryService().pair(new DevicePairRequest(pairing.code(), Map.of()));
        MockHttpServletRequest request = request("POST", "/api/v1/auth/tokens");
        request.addHeader("X-ClawAgent-Device-Id", paired.device().id());
        request.addHeader("X-ClawAgent-Device-Secret", paired.deviceSecret());
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = newInterceptor(properties, services).preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
        assertEquals("{\"error\":\"insufficient_device_scope\"}", response.getContentAsString());
    }

    @Test
    void rejectsInvalidCredentialWhenAuthIsRequired() throws Exception {
        ServerAuthProperties properties = new ServerAuthProperties();
        properties.setRequired(true);
        MockHttpServletRequest request = request("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = newInterceptor(properties, services()).preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertEquals("{\"error\":\"unauthorized\"}", response.getContentAsString());
    }

    private ApiTokenAuthInterceptor newInterceptor(ServerAuthProperties properties, TestServices services) {
        return new ApiTokenAuthInterceptor(properties, services.apiTokenService(), services.localUserSessionService(),
                services.deviceRegistryService());
    }

    private TestServices services() {
        LocalUserService localUserService = TestIdentityStores.localUserService(tempDir);
        return new TestServices(
                TestIdentityStores.apiTokenService(tempDir),
                localUserService,
                TestIdentityStores.localUserSessionService(tempDir, localUserService),
                TestIdentityStores.deviceRegistryService(tempDir));
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private record TestServices(
            ApiTokenService apiTokenService,
            LocalUserService localUserService,
            LocalUserSessionService localUserSessionService,
            DeviceRegistryService deviceRegistryService
    ) {
    }
}
