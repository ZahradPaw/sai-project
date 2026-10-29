package org.example;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import org.example.agents.*;
import org.example.models.*;
import org.example.repository.ScheduleRepository;
import org.example.repository.ScheduleRepositoryImpl;
import org.example.utils.DataLoader;

import java.util.*;
import java.util.stream.Collectors;

// Запуск клиента
public class ClientLauncher {
    public static void start(String host, String role, String[] args) throws Exception {
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, host);
        p.setParameter(Profile.MAIN_PORT, ServerLauncher.SERVER_PORT);
        AgentContainer cc = rt.createAgentContainer(p);
        System.out.println("✅ Клиент подключён к " + host + ":" +
                ServerLauncher.SERVER_PORT + " (роль: " + role + ")");

        // Выбор роли
        switch (role) {
            case "machines" -> launchMachinesAndWorkers(cc);
            case "parts"    -> launchProducts(cc);
            case "suppliers"-> launchSuppliers(cc);
            case "fault"    -> simulateFault(cc, args);
            default -> System.out.println("Неизвестная роль: " + role);
        }
    }

    // Роль со станками и рабочими
    private static void launchMachinesAndWorkers(AgentContainer cc) throws Exception {
        ScheduleRepository schedule = new ScheduleRepositoryImpl();
        List<Machine> machines = DataLoader.loadMachines("machines.json");
        for (Machine m : machines) {
            AgentController ac = cc.createNewAgent("machine-" + m.getId(),
                    MachineAgent.class.getName(),
                    new Object[]{m, schedule});
            ac.start();
            Thread.sleep(100);
        }
        List<Worker> workers = DataLoader.loadWorkers("workers.json");
        for (Worker w : workers) {
            AgentController ac = cc.createNewAgent("worker-" + w.getId(),
                    WorkerAgent.class.getName(),
                    new Object[]{w, schedule});
            ac.start();
            Thread.sleep(100);
        }
        System.out.println("🔧 Все станки и рабочие запущены и зарегистрированы в DF");
    }

    // Роль с изделиями и деталями
    private static void launchProducts(AgentContainer cc) throws Exception {
        ScheduleRepository schedule = new ScheduleRepositoryImpl();
        List<Product> products = DataLoader.loadProducts("products.json");
        List<Part> allParts = DataLoader.loadParts("parts.json");
        Map<String, Part> partMap = allParts.stream().collect(Collectors.toMap(Part::getId, x -> x));

        for (Product prod : products) {
            List<Part> prodParts = new ArrayList<>();
            for (String pid : prod.getPartIds()) {
                Part pt = partMap.get(pid);
                if (pt != null) prodParts.add(pt);
            }
            AgentController ac = cc.createNewAgent("product-" + prod.getId(),
                    ProductAgent.class.getName(),
                    new Object[]{prod, prodParts, schedule});
            ac.start();
            Thread.sleep(300);
        }
        System.out.println("📦 Все изделия запущены, детали ведут переговоры...");

        Thread.sleep(5000);
        collectAndWritePlan(cc);
    }

    // Сборка и создание календарного плана
    private static void collectAndWritePlan(AgentContainer cc) {
        try {
            // Создаём временного агента-сборщика, который опросит все станки
            AgentController collector = cc.createNewAgent(
                    "plan-collector-" + System.currentTimeMillis(),
                    PlanCollectorAgent.class.getName(),
                    new Object[]{}
            );
            collector.start();
            Thread.sleep(3000);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Роль с поставщиками для внешней МАС
    private static void launchSuppliers(AgentContainer cc) throws Exception {
        for (int i = 1; i <= 2; i++) {
            AgentController ac = cc.createNewAgent("supplier-" + i,
                    SupplierAgent.class.getName(),
                    new Object[]{"supplier-" + i});
            ac.start();
        }
        System.out.println("🏭 Внешнее МАС поставщиков запущено");
    }

    // Роль для создания аварийной ситуации
    private static void simulateFault(AgentContainer cc, String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Использование: client <ip> fault <machine:M1|worker:W1> [repair|recover]");
            return;
        }
        String spec = args[3];
        String action = args.length > 4 ? args[4] : null;

        String[] kindId = spec.split(":", 2);
        if (kindId.length != 2) {
            System.out.println("Формат: machine:M1 или worker:W1");
            return;
        }
        String kind = kindId[0];
        String id   = kindId[1];

        if (action == null) {
            action = "machine".equals(kind) ? "break" : "sick";
        }

        AgentController commander = cc.createNewAgent(
                "fault-commander-" + System.currentTimeMillis(),
                FaultCommanderAgent.class.getName(),
                new Object[]{kind + "-" + id, action});
        commander.start();

        Thread.sleep(5000);
        System.out.println("⚠ FaultCommanderAgent не завершился сам, выходим принудительно");
        System.exit(0);
    }
}
