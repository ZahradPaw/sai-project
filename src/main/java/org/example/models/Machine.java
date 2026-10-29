package org.example.models;

import lombok.Data;

// Станок
@Data
public class Machine {
    private String id;
    private String type;
    private double workStart;
    private double workEnd;
}
