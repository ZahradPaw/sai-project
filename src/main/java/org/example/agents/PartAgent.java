package org.example.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import lombok.Getter;
import lombok.Setter;
import org.example.behaviours.PartNegotiationBehaviour;
import org.example.models.Part;
import org.example.models.Product;
import org.example.models.ScheduleEntry;
import org.example.repository.ScheduleRepository;

import java.util.ArrayList;
import java.util.List;

// Агент детали
public class PartAgent extends Agent {
    @Getter private Part part;
    @Getter private Product product;
    @Getter private double dueDate;
    @Getter private ScheduleRepository schedule;
    @Getter @Setter private double currentTime = 0;
    @Setter @Getter private int nextOperationIndex = 0;
    @Getter private final List<ScheduleEntry> plannedEntries = new ArrayList<>();
    @Getter private PartNegotiationBehaviour negotiation;
    @Setter @Getter private boolean doneReported = false;
    private boolean replanScheduled = false;
    private boolean hasSkippedOperations = false;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 4) {
            doDelete();
            return;
        }
        this.part = (Part) args[0];
        this.product = (Product) args[1];
        this.dueDate = (Double) args[2];
        this.schedule = (ScheduleRepository) args[3];

        register();
        System.out.println("🔩 PartAgent " + part.getId() + " (изделие " + product.getId() + ") запущен");

        negotiation = new PartNegotiationBehaviour(this);
        addBehaviour(negotiation);
        addBehaviour(new FaultListenerBehaviour(this));
    }

    // Регистрация
    private void register() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("part");
        sd.setName("part-" + part.getId());
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        }
        catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    // Добавление записи в план
    public void addPlannedEntry(ScheduleEntry e) {
        plannedEntries.add(e);
    }

    // Уведомление изделия о полном или частичном завершении операций
    public void notifyProductDone() {
        if (doneReported) return;
        doneReported = true;

        String status = hasSkippedOperations ? "INCOMPLETE" : "OK";

        ACLMessage done = new ACLMessage(ACLMessage.INFORM);
        done.addReceiver(new AID("product-" + product.getId(), AID.ISLOCALNAME));
        done.setConversationId("part-done");
        done.setContent(part.getId() + ";" + status);
        send(done);

        if (hasSkippedOperations) {
            System.out.println("⚠ PartAgent " + part.getId() +
                    " завершил, но не все операции запланированы (есть пропуски)");
        } else {
            System.out.println("🏁 PartAgent " + part.getId() + " завершил все операции");
        }
    }

    // Откат операции
    public void rollbackOperation(ScheduleEntry failed, String reason) {
        System.out.println("♻ PartAgent " + part.getId() +
                ": откат операции " + failed.getOperationId() + " (" + reason + ")");

        try {
            schedule.releaseEntry(failed);
        }
        catch (Exception e) {
            System.err.println("⚠ Ошибка releaseEntry: " + e.getMessage());
        }

        // Уведомляем второй ресурс пары, чтобы он удалил запись из своего журнала
        sendCancel(failed, reason);

        plannedEntries.remove(failed);
        currentTime = failed.getStart();
        doneReported = false;

        for (int i = 0; i < part.getOperations().size(); i++) {
            if (part.getOperations().get(i).getId().equals(failed.getOperationId())) {
                nextOperationIndex = i;
                break;
            }
        }

        if (negotiation != null) removeBehaviour(negotiation);
        negotiation = new PartNegotiationBehaviour(this);
        addBehaviour(negotiation);

        // Запуск перепланирования
        scheduleReplan();
    }

    // Отправка сообщения отмены при аварии станка или рабочего
    private void sendCancel(ScheduleEntry entry, String reason) {
        // Определяем, у кого произошла авария: станка или рабочего
        String targetName;
        if ("machine_broken".equals(reason)) {
            targetName = "worker-" + entry.getWorkerId();
        } else {
            targetName = "machine-" + entry.getMachineId();
        }

        ACLMessage m = new ACLMessage(ACLMessage.CANCEL);
        m.addReceiver(new AID(targetName, AID.ISLOCALNAME));
        m.setConversationId("op-cfp");
        m.setContent(entry.getStart() + ";" + entry.getEnd()
                + ";" + entry.getProductId()
                + ";" + entry.getPartId()
                + ";" + entry.getOperationId()
                + ";" + entry.getMachineId()
                + ";" + entry.getWorkerId());
        send(m);
        System.out.println("📤 PartAgent " + part.getId() + ": CANCEL -> " + targetName);
    }

    // Запуск перепланирования всего расписания
    private void scheduleReplan() {
        if (replanScheduled) return;
        replanScheduled = true;

        addBehaviour(new WakerBehaviour(this, 8000) {
            @Override
            protected void onWake() {
                replanScheduled = false;

                String collectorName = "plan-collector-" + getLocalName() + "-" + System.currentTimeMillis();
                try {
                    jade.wrapper.AgentController ac = getContainerController().createNewAgent(
                            collectorName,
                            PlanCollectorAgent.class.getName(),
                            new Object[]{}
                    );
                    ac.start();
                    System.out.println("🔄 Запущен PlanCollectorAgent для обновления плана");
                } catch (jade.wrapper.StaleProxyException e) {
                    // PlanCollectorAgent уже активен, пропускаем запуск
                } catch (Exception e) {
                    System.err.println("⚠ Не удалось запустить PlanCollectorAgent: " + e.getMessage());
                }
            }
        });
    }

    // Проверка, что агент составления плана уже работает
    private boolean isCollectorAlive() {
        try {
            DFAgentDescription tpl = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("plan-collector");
            tpl.addServices(sd);
            DFAgentDescription[] existing = DFService.search(this, tpl);
            return existing.length > 0;
        } catch (FIPAException e) {
            return false;
        }
    }

    // Проверка наличия пропущенных операций
    public boolean hasSkippedOperations() {
        return hasSkippedOperations;
    }

    // Пометка пропущенных операций
    public void markOperationSkipped() {
        hasSkippedOperations = true;
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        }
        catch (FIPAException ignored) {}
    }

    // Поведение прослушивания аварийных ситуаций
    private static class FaultListenerBehaviour extends CyclicBehaviour {
        private final PartAgent agent;

        FaultListenerBehaviour(PartAgent a) {
            super(a);
            this.agent = a;
        }

        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchConversationId("fault-notify");
            ACLMessage msg = agent.receive(mt);
            if (msg == null) {
                block();
                return;
            }
            String content = msg.getContent();
            if (content == null) return;
            String[] p = content.split(";");
            if (p.length < 2) return;
            String reason = p[0];
            String resourceId = p[1];

            // Откат операций детали при аварии
            for (ScheduleEntry e : new ArrayList<>(agent.getPlannedEntries())) {
                boolean hit = ("machine_broken".equals(reason) && resourceId.equals(e.getMachineId()))
                        || ("worker_sick".equals(reason)    && resourceId.equals(e.getWorkerId()));
                if (hit) {
                    agent.rollbackOperation(e, reason);
                    return;
                }
            }
        }
    }
}
