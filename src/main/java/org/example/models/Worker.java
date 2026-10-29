package org.example.models;

import lombok.Data;

// Рабочий
@Data
public class Worker {
    private String id;
    private String name;
    private String skill;
    private double shiftStart;
    private double shiftEnd;
}
