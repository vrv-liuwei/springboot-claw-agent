package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.server.dto.DevicePairRequest;
import com.github.clawagent.server.dto.DevicePairResponse;
import com.github.clawagent.server.dto.DevicePairingCodeResponse;
import com.github.clawagent.server.dto.DevicePairingCreateRequest;
import com.github.clawagent.server.dto.DevicePermissionUpdateRequest;
import com.github.clawagent.server.dto.DeviceRegisterRequest;
import com.github.clawagent.server.dto.DeviceSecretVerifyRequest;
import com.github.clawagent.server.dto.DeviceSecretVerifyResponse;
import com.github.clawagent.server.dto.DeviceSecretRotateResponse;
import com.github.clawagent.server.dto.DeviceUserBindRequest;
import com.github.clawagent.server.dto.DeviceView;
import com.github.clawagent.server.service.DeviceRegistryService;
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
 * DeviceController 提供本地设备登记入口。
 * 当前提供本地登记、配对码和设备密钥基础能力，为后续桌面端和企业 Device Pairing 预留稳定 API。
 */
@RestController
@RequestMapping("/api/v1/auth/devices")
public class DeviceController {
    private final DeviceRegistryService deviceRegistryService;
    private final AgentEventStore eventStore;

    public DeviceController(DeviceRegistryService deviceRegistryService,
                            @Qualifier("agentEventStore") AgentEventStore eventStore) {
        this.deviceRegistryService = deviceRegistryService;
        this.eventStore = eventStore;
    }

    @GetMapping
    public List<DeviceView> devices() {
        return deviceRegistryService.list();
    }

    @PostMapping
    public DeviceView register(@RequestBody DeviceRegisterRequest request) {
        DeviceView view = deviceRegistryService.register(request);
        recordDeviceAudit("auth.device.registered", "设备已登记", view);
        return view;
    }

    @PostMapping("/pairing-codes")
    public DevicePairingCodeResponse createPairingCode(@RequestBody DevicePairingCreateRequest request) {
        DevicePairingCodeResponse response = deviceRegistryService.createPairingCode(request);
        recordDeviceAudit("auth.device.pairing_code_created", "设备配对码已创建", response.device());
        return response;
    }

    @PostMapping("/pair")
    public DevicePairResponse pair(@RequestBody DevicePairRequest request) {
        DevicePairResponse response = deviceRegistryService.pair(request);
        recordDeviceAudit("auth.device.paired", "设备已完成配对", response.device());
        return response;
    }

    @PostMapping("/{deviceId}/heartbeat")
    public DeviceView heartbeat(@PathVariable String deviceId) {
        DeviceView view = deviceRegistryService.heartbeat(deviceId);
        recordDeviceAudit("auth.device.heartbeat", "设备心跳已更新", view);
        return view;
    }

    @PostMapping("/{deviceId}/verify")
    public DeviceSecretVerifyResponse verify(@PathVariable String deviceId,
                                             @RequestBody DeviceSecretVerifyRequest request) {
        DeviceSecretVerifyResponse response = deviceRegistryService.verifySecret(deviceId, request);
        if (response.verified()) {
            eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO",
                    "auth.device.verified", "设备密钥校验成功", Map.of("deviceId", deviceId, "status", response.status())));
        }
        return response;
    }

    @PostMapping("/{deviceId}/secret/rotate")
    public DeviceSecretRotateResponse rotateSecret(@PathVariable String deviceId) {
        DeviceSecretRotateResponse response = deviceRegistryService.rotateSecret(deviceId);
        recordDeviceAudit("auth.device.secret_rotated", "设备密钥已轮换", response.device());
        return response;
    }

    @PostMapping("/{deviceId}/user")
    public DeviceView bindUser(@PathVariable String deviceId,
                               @RequestBody DeviceUserBindRequest request) {
        DeviceView view = deviceRegistryService.bindUser(deviceId, request);
        recordDeviceAudit("auth.device.user_bound", "设备用户绑定已更新", view);
        return view;
    }

    @PostMapping("/{deviceId}/permissions")
    public DeviceView updatePermissions(@PathVariable String deviceId,
                                        @RequestBody DevicePermissionUpdateRequest request) {
        DeviceView view = deviceRegistryService.updatePermissions(deviceId, request);
        recordDeviceAudit("auth.device.permissions_updated", "设备权限绑定已更新", view);
        return view;
    }

    @DeleteMapping("/{deviceId}")
    public DeviceView revoke(@PathVariable String deviceId) {
        DeviceView view = deviceRegistryService.revoke(deviceId);
        recordDeviceAudit("auth.device.revoked", "设备已撤销", view);
        return view;
    }

    private void recordDeviceAudit(String type, String message, DeviceView view) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("deviceId", view.id());
        details.put("name", view.name());
        details.put("deviceType", view.type());
        details.put("status", view.status());
        details.put("permissionMode", view.permissionMode());
        details.put("boundUserId", view.boundUserId() == null ? "" : view.boundUserId());
        details.put("boundUsername", view.boundUsername() == null ? "" : view.boundUsername());
        details.put("approvedToolCount", String.valueOf(view.approvedToolIds() == null ? 0 : view.approvedToolIds().size()));
        details.put("metadataCount", String.valueOf(view.metadata() == null ? 0 : view.metadata().size()));
        // 设备 metadata、配对码和密钥都可能包含敏感信息，审计只保留最小识别摘要。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }
}
