package org.example.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.models.*;

import java.io.File;
import java.util.List;
import java.util.Map;

// Загрузчик входных данных из JSON
public class DataLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static List<Product> loadProducts(String f) throws Exception {
        Map<String, List<Product>> d = MAPPER.readValue(new File("src/main/resources/" + f),
                new TypeReference<Map<String, List<Product>>>() {});
        return d.get("products");
    }

    public static List<Part> loadParts(String f) throws Exception {
        Map<String, List<Part>> d = MAPPER.readValue(new File("src/main/resources/" + f),
                new TypeReference<Map<String, List<Part>>>() {});
        return d.get("parts");
    }

    public static List<Machine> loadMachines(String f) throws Exception {
        Map<String, List<Machine>> d = MAPPER.readValue(new File("src/main/resources/" + f),
                new TypeReference<Map<String, List<Machine>>>() {});
        return d.get("machines");
    }

    public static List<Worker> loadWorkers(String f) throws Exception {
        Map<String, List<Worker>> d = MAPPER.readValue(new File("src/main/resources/" + f),
                new TypeReference<Map<String, List<Worker>>>() {});
        return d.get("workers");
    }
}
