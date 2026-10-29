package org.example.models;

import lombok.Data;
import java.util.List;

// Изделие
@Data
public class Product {
    private String id;
    private String name;
    private double dueDate;               // минуты от начала смены
    private List<String> partIds;         // какие детали входят
}
