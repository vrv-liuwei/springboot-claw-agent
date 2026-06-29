package com.github.clawagent.spi;

import java.util.List;

/**
 * 支持图片输入的模型客户端扩展。
 * 普通 ModelClient 保持纯文本接口，只有最终回复阶段需要原生看图时才走该接口。
 */
public interface MultimodalModelClient extends ModelClient {
    String chatWithImages(List<ChatMessage> messages, List<ModelImageInput> images, ChatOptions options);
}
