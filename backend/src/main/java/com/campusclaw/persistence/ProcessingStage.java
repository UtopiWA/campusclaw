package com.campusclaw.persistence;

/** 标识材料后台处理所处阶段，失败时保留最后阶段供教师判断。 */
public enum ProcessingStage {
    QUEUED,
    PARSING,
    INDEXING,
    COMPLETED
}
