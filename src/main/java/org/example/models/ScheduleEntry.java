package org.example.models;

import lombok.Data;

// Элемент расписания
@Data
public class ScheduleEntry {
    private String machineId;
    private String workerId;
    private String productId;
    private String partId;
    private String operationId;
    private double start;
    private double end;
}
