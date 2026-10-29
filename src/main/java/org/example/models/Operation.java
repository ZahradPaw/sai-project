package org.example.models;

import lombok.Data;

// Технологическая операция
@Data
public class Operation {
    private String id;
    private String name;
    private String requiredMachineType;
    private String requiredSkill;
    private double duration;
    private double setupTime;
}
