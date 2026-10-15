package com.campusclaw.common;

/** 表示请求与当前资源状态冲突，调用方可调整操作后重试。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
