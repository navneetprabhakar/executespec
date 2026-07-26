package com.navneet.executespec.agent;

import java.util.List;

record OrderStatusContract(
    List<String> values, String activeValue, String constraint, String producerTaskId) {}
