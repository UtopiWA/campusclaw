package com.campusclaw.common;

/** 表示 MCP 等外部依赖可访问但握手、鉴权或协议响应失败。 */
public class BadGatewayException extends RuntimeException {
    public BadGatewayException(String message) {
        super(message);
    }
}
