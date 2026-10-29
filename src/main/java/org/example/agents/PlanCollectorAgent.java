package org.example.agents;

import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.example.models.ScheduleEntry;
import org.example.utils.PlanWriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Служебный агент формирования плана
public class PlanCollectorAgent extends Agent {
    private boolean active = false;

    @Override
    protected void setup() {
        // Проверяем, нет ли уже активного сборщика
        try {
            DFAgentDescription tpl = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("plan-collector");
            tpl.addServices(sd);
            DFAgentDescription[] existing = DFService.search(this, tpl);
            if (existing.length > 0) {
                doDelete();
                return;
            }

            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(getAID());
            ServiceDescription ownSd = new ServiceDescription();
            ownSd.setType("plan-collector");
            ownSd.setName("plan-collector-" + System.currentTimeMillis());
            dfd.addServices(ownSd);
            DFService.register(this, dfd);
            active = true;
        }
        catch (FIPAException e) {
            e.printStackTrace();
            doDelete();
            return;
        }

        System.out.println("🗂 PlanCollectorAgent запущен, опрос станков...");
        new Thread(this::collectAndWrite).start();
    }

    // Сбор записей журналов со всех агентов
    private void collectAndWrite() {
        try {
            Thread.sleep(500);

            DFAgentDescription tpl = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("machine");
            tpl.addServices(sd);

            DFAgentDescription[] machines;
            try {
                machines = DFService.search(this, tpl);
            }
            catch (FIPAException e) {
                e.printStackTrace();
                doDelete();
                return;
            }

            if (machines.length == 0) {
                System.out.println("⚠ PlanCollectorAgent: не нашёл ни одного станка");
                PlanWriter.write("plan.txt", List.of(), 0, 0);
                doDelete();
                return;
            }

            // Опрос всех станков
            ACLMessage req = new ACLMessage(ACLMessage.QUERY_REF);
            for (DFAgentDescription d : machines) req.addReceiver(d.getName());
            req.setConversationId("op-cfp");
            req.setReplyWith("collect-" + System.currentTimeMillis());
            send(req);

            MessageTemplate mt = MessageTemplate.and(
                    MessageTemplate.MatchConversationId("op-cfp"),
                    MessageTemplate.MatchPerformative(ACLMessage.INFORM));

            List<ScheduleEntry> all = new ArrayList<>();
            int received = 0;
            long deadline = System.currentTimeMillis() + 5000;

            // Сборка ответов со всех станков (ожидание до 5 сек)
            while (received < machines.length && System.currentTimeMillis() < deadline) {
                ACLMessage r = receive(mt);
                if (r == null) {
                    Thread.sleep(100);
                    continue;
                }
                received++;
                String content = r.getContent();
                if (content == null || content.isEmpty()) continue;
                for (String line : content.split("\n")) {
                    if (line.isBlank()) continue;
                    String[] p = line.split("\\|");
                    if (p.length < 7) continue;
                    ScheduleEntry e = new ScheduleEntry();
                    e.setMachineId(p[0]);
                    e.setWorkerId(p[1]);
                    e.setProductId(p[2]);
                    e.setPartId(p[3]);
                    e.setOperationId(p[4]);
                    e.setStart(Double.parseDouble(p[5]));
                    e.setEnd(Double.parseDouble(p[6]));
                    all.add(e);
                }
            }

            // Поиск изделий и получение сроков
            Map<String, Double> dueMap = new HashMap<>();
            DFAgentDescription tplP = new DFAgentDescription();
            ServiceDescription sdP = new ServiceDescription();
            sdP.setType("product");
            tplP.addServices(sdP);
            DFAgentDescription[] products = new DFAgentDescription[0];
            try {
                products = DFService.search(this, tplP);
            }
            catch (FIPAException e) {
                e.printStackTrace();
            }

            if (products.length > 0) {
                // Опрос всех изделий
                ACLMessage reqP = new ACLMessage(ACLMessage.QUERY_REF);
                for (DFAgentDescription d : products) reqP.addReceiver(d.getName());
                reqP.setConversationId("due-date");
                reqP.setReplyWith("due-" + System.currentTimeMillis());
                send(reqP);

                MessageTemplate mtP = MessageTemplate.and(
                        MessageTemplate.MatchConversationId("due-date"),
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM));

                int gotP = 0;
                long deadlineP = System.currentTimeMillis() + 5000;

                // Сборка ответов со всех изделий (ожидание до 5 сек)
                while (gotP < products.length && System.currentTimeMillis() < deadlineP) {
                    ACLMessage r = receive(mtP);
                    if (r == null) {
                        Thread.sleep(50);
                        continue;
                    }
                    gotP++;
                    String[] p = r.getContent().split(";");
                    if (p.length >= 2) {
                        try {
                            String productId = p[0];
                            double due = Double.parseDouble(p[1]);
                            dueMap.put(productId, due);
                        }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }

            // время окончания всех операций
            double makespan = all.stream().mapToDouble(ScheduleEntry::getEnd).max().orElse(0);

            // суммарное опоздание изделий
            double tardiness = 0;
            for (var e : dueMap.entrySet()) {
                String productId = e.getKey();
                double due = e.getValue();
                double lastEnd = all.stream()
                        .filter(x -> productId.equals(x.getProductId()))
                        .mapToDouble(ScheduleEntry::getEnd)
                        .max().orElse(0);
                if (lastEnd > due) tardiness += lastEnd - due;
            }

            // Запись готового плана в файл
            PlanWriter.write("plan.txt", all, makespan, tardiness);
            System.out.println("🗂 PlanCollectorAgent: собрано " + all.size() +
                    " операций, время окончания всех операций=" + makespan +
                    ", суммарное опоздание изделий=" + tardiness
            );

        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            doDelete();
        }
    }

    @Override
    protected void takeDown() {
        if (active) {
            try {
                DFService.deregister(this);
            }
            catch (FIPAException ignored) {}
        }
        System.out.println("🗂 PlanCollectorAgent завершил работу");
    }
}
