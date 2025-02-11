package com.arextest.schedule.model;

import lombok.Getter;

/**
 * @author jmo
 * @since 2021/9/15
 */
public enum CaseSendStatusType {
    WAIT_HANDLING(0),
    SUCCESS(1),
    EXCEPTION_FAILED(100),
    READY_DEPENDENCY_FAILED(101),
    REPLAY_CASE_NOT_FOUND(102),
    REPLAY_RESULT_NOT_FOUND(103),
    ;
    @Getter
    final int value;

    CaseSendStatusType(int value) {
        this.value = value;
    }
}