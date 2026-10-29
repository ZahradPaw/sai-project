package org.example.utils;

import org.example.models.ScheduleEntry;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

// Запись плана в текстовый файл
public class PlanWriter {
    public static void write(String fileName, List<ScheduleEntry> entries,
                             double makespan, double totalTardiness) throws IOException {
        File dir = new File("plan_output");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, fileName);

        try (PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8)) {

            w.println("--- КАЛЕНДАРНЫЙ ПЛАН ПРОИЗВОДСТВА ---\n");
            w.printf(" Время окончания всех операций: %.2f мин%n", makespan);
            w.printf(" Суммарное запаздывание по изделиям: %.2f мин%n", totalTardiness);
            w.println("-----------------------------------------------------------------------------");
            w.printf("| %-14s | %-14s | %-12s | %-10s | %-10s |%n",
                    "ИЗДЕЛИЕ", "ДЕТАЛЬ/ОПЕРАЦИЯ", "СТАНОК", "РАБОЧИЙ", "ИНТЕРВАЛ");
            w.println("-----------------------------------------------------------------------------");

            entries.sort((a, b) -> Double.compare(a.getStart(), b.getStart()));
            for (ScheduleEntry e : entries) {
                w.printf("| %-14s | %-14s | %-12s | %-10s | %5.1f-%5.1f |%n",
                        e.getProductId(),
                        e.getPartId() + "/" + e.getOperationId(),
                        e.getMachineId(),
                        e.getWorkerId(),
                        e.getStart(), e.getEnd());
            }
            w.println("-----------------------------------------------------------------------------");
            w.println("Дата создания: " + new java.util.Date());
        }
        System.out.println("📄 План сохранён: " + f.getAbsolutePath());
    }
}
