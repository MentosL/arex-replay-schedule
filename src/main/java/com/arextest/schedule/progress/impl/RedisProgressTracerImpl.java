package com.arextest.schedule.progress.impl;

import com.arextest.common.cache.CacheProvider;
import com.arextest.schedule.model.ReplayActionCaseItem;
import com.arextest.schedule.model.ReplayActionItem;
import com.arextest.schedule.model.ReplayPlan;
import com.arextest.schedule.progress.ProgressEvent;
import com.arextest.schedule.progress.ProgressTracer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author jmo
 * @since 2021/10/11
 */
@Slf4j
@Component
final class RedisProgressTracerImpl implements ProgressTracer {
    private static final long SEVEN_DAYS_EXPIRE = TimeUnit.DAYS.toSeconds(7);
    private static final byte[] PLAN_TOTAL_KEY = "plan_total".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAN_ACTION_TOTAL_KEY = "plan_action_total".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAN_FINISH_KEY = "plan_finish".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PLAN_UPDATE_TIME_KEY = "plan_update_time".getBytes(StandardCharsets.UTF_8);

    @Resource
    private ProgressEvent progressEvent;
    @Resource
    private CacheProvider redisCacheProvider;

    @Override
    public void initTotal(ReplayPlan replayPlan) {
        String planId = replayPlan.getId();
        int value = replayPlan.getCaseTotalCount();
        byte[] totalKey = toPlanTotalKeyBytes(planId);
        setupRedisNxWithExpire(totalKey, valueToBytes(value));
        int actionCaseCount;
        for (ReplayActionItem replayActionItem : replayPlan.getReplayActionItemList()) {
            actionCaseCount = replayActionItem.getReplayCaseCount();
            if (actionCaseCount > 0) {
                setupRedisNxWithExpire(toPlanActionTotalKeyBytes(replayActionItem.getId()),
                        String.valueOf(actionCaseCount).getBytes(StandardCharsets.UTF_8));
            }
        }
        this.refreshUpdateTime(planId);
    }

    private void setupRedisNxWithExpire(byte[] key, byte[] value) {
        try {
            redisCacheProvider.putIfAbsent(key, SEVEN_DAYS_EXPIRE, value);
        } catch (Throwable throwable) {
            LOGGER.error("setup redis Nx With expire error: {}", throwable.getMessage(), throwable);
        }
    }

    private byte[] valueToBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private byte[] valueToBytes(long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private byte[] toPlanActionTotalKeyBytes(String actionId) {
        return allocateArray(PLAN_ACTION_TOTAL_KEY, actionId.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toPlanUpdateTimeKeyBytes(String planId) {
        return allocateArray(PLAN_UPDATE_TIME_KEY, planId.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toPlanTotalKeyBytes(String planId) {
        return allocateArray(PLAN_TOTAL_KEY, planId.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] toPlanFinishKeyBytes(String planId) {
        return allocateArray(PLAN_FINISH_KEY, planId.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] allocateArray(byte[] srcKey, byte[] paramKey) {
        return ByteBuffer.allocate(srcKey.length + paramKey.length).put(srcKey).put(paramKey).array();
    }

    @Override
    public void finishOne(ReplayActionCaseItem caseItem) {
        ReplayActionItem replayActionItem = caseItem.getParent();
        doReplayActionFinish(replayActionItem);
        ReplayPlan replayPlan = replayActionItem.getParent();
        doPlanFinish(replayPlan);
        this.refreshUpdateTime(replayPlan.getId());
    }

    private void doPlanFinish(ReplayPlan replayPlan) {
        String planId = replayPlan.getId();
        try {
            Long finished = doWithRetry(() -> redisCacheProvider.incrValue(toPlanFinishKeyBytes(planId)));
            if (finished != null && finished == replayPlan.getCaseTotalCount()) {
                progressEvent.onReplayPlanFinish(replayPlan);
            }
        } catch (Throwable throwable) {

            LOGGER.error("do plan finish error: {} ,plan id: {}", throwable.getMessage(), planId, throwable);
        }
    }

    private Long doWithRetry(Supplier<Long> action) {
        try {
            return action.get();
        } catch (Throwable throwable) {
            LOGGER.error("do  doWithRetry error: {}", throwable.getMessage(), throwable);
            return action.get();
        }
    }

    private void doReplayActionFinish(ReplayActionItem replayActionItem) {
        String actionId = replayActionItem.getId();
        try {
            Long finished = doWithRetry(() -> redisCacheProvider.decrValue(toPlanActionTotalKeyBytes(actionId)));
            if (finished != null && finished == 0) {
                progressEvent.onActionComparisonFinish(replayActionItem);
            }
        } catch (Throwable throwable) {
            LOGGER.error("do plan action finish error: {} ,action id: {}", throwable.getMessage(), actionId, throwable);
        }
    }

    @Override
    public double finishPercent(String planId) {
        byte[] totalBytes = redisCacheProvider.get(toPlanTotalKeyBytes(planId));
        if (totalBytes == null) {
            return Double.NaN;
        }
        byte[] finishBytes = redisCacheProvider.get(toPlanFinishKeyBytes(planId));
        if (finishBytes == null) {
            return Double.NaN;
        }
        String finishText = new String(finishBytes);
        int total = byteArrayToInt(totalBytes);
        int finish = StringUtils.isEmpty(finishText) ? 0 : Integer.parseInt(finishText);
        if (total != 0) {
            return ((double) finish / total) * 100;
        }
        return Double.NaN;
    }

    @Override
    public long lastUpdateTime(String planId) {
        byte[] bytes = redisCacheProvider.get(toPlanUpdateTimeKeyBytes(planId));
        if (bytes == null || bytes.length != Long.BYTES) {
            return 0;
        }
        return ByteBuffer.wrap(bytes).getLong();
    }

    private void refreshUpdateTime(String planId) {
        long now = System.currentTimeMillis();
        try {
            redisCacheProvider.put(toPlanUpdateTimeKeyBytes(planId), SEVEN_DAYS_EXPIRE, valueToBytes(now));
        } catch (Throwable throwable) {
            LOGGER.error("refresh plan last update time error:{}, plan id : {}", throwable.getMessage(), planId,
                    throwable);
        }
    }

    private int byteArrayToInt(byte[] bytes) {
        if (bytes.length != Integer.BYTES) {
            return 0;
        }
        return ByteBuffer.wrap(bytes).getInt();
    }

}