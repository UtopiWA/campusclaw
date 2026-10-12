package com.campusclaw.persistence;

/** 面向 API 的稳定失败分类；原始异常和供应商信息不得直接暴露。 */
public enum ProcessingFailureCode {
    INVALID_TEXT,
    DEPENDENCY_UNAVAILABLE,
    PROCESSING_ERROR
}
