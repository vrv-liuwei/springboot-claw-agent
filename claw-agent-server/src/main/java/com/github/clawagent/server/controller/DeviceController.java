package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.server.dto.DeviceRegisterRequest;
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
 * 当前不做强配对鉴权，只记录接入端身份，为后续桌面端和企业 Device Pairing 预留稳定 API。
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

    @PostMapping("/{deviceId}/heartbeat")
    public DeviceView heartbeat(@PathVariable String deviceId) {
        DeviceView view = deviceRegistryService.heartbeat(deviceId);
        recordDeviceAudit("auth.device.heartbeat", "设备心跳已更新", view);
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
        details.put("metadataCount", String.valueOf(view.metadata() == null ? 0 : view.metadata().size()));
        // 设备 metadata 可能包含客户端环境信息，审计只保留最小识别摘要。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }
}
