package org.example.models;

import lombok.Data;
import java.util.List;

// Деталь изделия
@Data
public class Part {
    private String id;
    private String name;
    private List<Operation> operations;   // последовательность операций
}
