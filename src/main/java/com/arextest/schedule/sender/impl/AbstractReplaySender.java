package com.arextest.schedule.sender.impl;

import com.arextest.schedule.common.CommonConstant;
import com.arextest.schedule.model.ReplayActionCaseItem;
import com.arextest.schedule.model.deploy.ServiceInstance;
import com.arextest.schedule.sender.ReplaySendResult;
import com.arextest.schedule.sender.ReplaySender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Resource;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
abstract class AbstractReplaySender implements ReplaySender {
    @Resource
    private MockCachePreLoader mockCachePreLoader;
    @Resource
    private ObjectMapper objectMapper;

    protected void bindSendResult(ReplayActionCaseItem caseItem, ReplaySendResult sendResult) {
        caseItem.setTargetResultId(sendResult.getTraceId());
        caseItem.setSendStatus(sendResult.getStatusType().getValue());
    }

    protected void before(String recordId, int replayPlanType) {
        if (StringUtils.isNotEmpty(recordId)) {
            mockCachePreLoader.fillMockSource(recordId, replayPlanType);
        }
    }


    protected void after(ReplayActionCaseItem caseItem) {
        mockCachePreLoader.removeMockSource(caseItem.getRecordId());
    }

    protected Map<String, String> newHeadersIfEmpty(Map<String, String> source) {
        if (MapUtils.isEmpty(source)) {
            return new HashMap<>();
        }
        return source;
    }

    protected boolean isReplayRequest(Map<?, ?> requestHeaders) {
        return MapUtils.isNotEmpty(requestHeaders) && requestHeaders.containsKey(CommonConstant.AREX_RECORD_ID);
    }

    protected String encodeResponseAsString(Object responseBody) {
        if (responseBody == null) {
            return null;
        }
        if (responseBody instanceof String) {
            return (String) responseBody;
        }
        if (responseBody instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) responseBody);
        }
        try {
            return objectMapper.writeValueAsString(responseBody);
        } catch (JsonProcessingException e) {
            LOGGER.warn("encodeAsString error:{}", e.getMessage(), e);
        }
        return null;
    }

    protected ServiceInstance selectLoadBalanceInstance(String caseItemId, List<ServiceInstance> serviceInstances) {
        if (CollectionUtils.isEmpty(serviceInstances)) {
            return null;
        }
        int index = Math.abs(caseItemId.hashCode()) % serviceInstances.size();
        return serviceInstances.get(index);
    }
}