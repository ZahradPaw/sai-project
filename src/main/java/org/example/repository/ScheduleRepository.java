package org.example.repository;

import org.example.models.ScheduleEntry;

// Интерфейс планировщика расписание
public interface ScheduleRepository {
    boolean isMachineFree(String machineId, double start, double end);
    boolean isWorkerFree(String workerId, double start, double end);
    void book(String machineId, String workerId, ScheduleEntry entry);
    void releaseEntry(ScheduleEntry entry);
}
