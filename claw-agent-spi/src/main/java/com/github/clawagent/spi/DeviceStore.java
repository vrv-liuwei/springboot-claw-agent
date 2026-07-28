package com.github.clawagent.spi;

import java.util.List;

/**
 * 设备登记存储边界。
 * 设备配对、密钥校验和设备级权限策略通过该接口持久化。
 */
public interface DeviceStore {
    List<DeviceRecord> read();

    void write(List<DeviceRecord> records);
}
