package com.github.clawagent.spi;

/**
 * 模型多模态图片输入。
 * path 指向已经落地到本机的图片文件，具体 provider adapter 负责编码成自己的请求格式。
 */
public record ModelImageInput(String path, String mimeType, String name) {
}
