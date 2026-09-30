package com.campusclaw.common;

/** 对外部依赖故障进行脱敏，禁止向 API 响应传播供应商正文、地址或凭据。 */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String dependency) {
        super(dependency + " is temporarily unavailable");
    }
}
