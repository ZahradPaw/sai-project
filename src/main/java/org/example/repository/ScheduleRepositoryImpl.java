package org.example.repository;

import org.example.models.ScheduleEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

// Реализация планировщика расписания
public class ScheduleRepositoryImpl implements ScheduleRepository {
    private final Map<String, List<ScheduleEntry>> machineSchedule = new ConcurrentHashMap<>();
    private final Map<String, List<ScheduleEntry>> workerSchedule = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public boolean isMachineFree(String machineId, double start, double end) {
        return isFree(machineSchedule.getOrDefault(machineId, List.of()), start, end);
    }

    @Override
    public boolean isWorkerFree(String workerId, double start, double end) {
        return isFree(workerSchedule.getOrDefault(workerId, List.of()), start, end);
    }

    // Поиск свободных интервалов
    private boolean isFree(List<ScheduleEntry> list, double start, double end) {
        for (ScheduleEntry e : list) {
            if (start < e.getEnd() && end > e.getStart()) return false;
        }
        return true;
    }

    // Закрепление записи в расписании
    @Override
    public void book(String machineId, String workerId, ScheduleEntry entry) {
        lock.lock();
        try {
            machineSchedule.computeIfAbsent(machineId, k -> new ArrayList<>()).add(entry);
            workerSchedule.computeIfAbsent(workerId, k -> new ArrayList<>()).add(entry);
        } finally {
            lock.unlock();
        }
    }

    // Удаление записи
    @Override
    public void releaseEntry(ScheduleEntry entry) {
        lock.lock();
        try {
            List<ScheduleEntry> ms = machineSchedule.get(entry.getMachineId());
            if (ms != null) {
                ms.removeIf(e -> sameEntry(e, entry));
            }
            List<ScheduleEntry> ws = workerSchedule.get(entry.getWorkerId());
            if (ws != null) {
                ws.removeIf(e -> sameEntry(e, entry));
            }
        } finally {
            lock.unlock();
        }
    }

    // Сравнение записей по ключевым полям
    private boolean sameEntry(ScheduleEntry a, ScheduleEntry b) {
        return Objects.equals(a.getMachineId(), b.getMachineId())
                && Objects.equals(a.getWorkerId(), b.getWorkerId())
                && Objects.equals(a.getProductId(), b.getProductId())
                && Objects.equals(a.getPartId(), b.getPartId())
                && Objects.equals(a.getOperationId(), b.getOperationId())
                && Math.abs(a.getStart() - b.getStart()) < 0.001
                && Math.abs(a.getEnd() - b.getEnd()) < 0.001;
    }
}
