package org.example.agents;

import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import lombok.Getter;
import org.example.behaviours.FaultHandlerBehaviour;
import org.example.behaviours.WorkerScheduleBehaviour;
import org.example.models.ScheduleEntry;
import org.example.models.Worker;
import org.example.repository.ScheduleRepository;

import java.util.*;

// Агент рабочего
public class WorkerAgent extends Agent {
    @Getter private Worker worker;
    @Getter private ScheduleRepository schedule;
    @Getter private final List<ScheduleEntry> localEntries = new ArrayList<>();
    @Getter private volatile boolean sick = false;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 2) {
            doDelete();
            return;
        }
        this.worker = (Worker) args[0];
        this.schedule = (ScheduleRepository) args[1];

        register();
        addBehaviour(new WorkerScheduleBehaviour(this));
        addBehaviour(new FaultHandlerBehaviour(this, "worker"));

        System.out.println("👷 WorkerAgent " + worker.getId() + " (" + worker.getSkill() + ") запущен");
    }

    // Регистрация
    private void register() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("worker");
        sd.setName("worker-" + worker.getId());
        sd.addProperties(new jade.domain.FIPAAgentManagement.Property("id", worker.getId()));
        sd.addProperties(new jade.domain.FIPAAgentManagement.Property("skill", worker.getSkill()));
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        }
        catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    // Заболевание рабочего
    public void makeSick() {
        sick = true;
        System.out.println("🤒 Рабочий " + worker.getId() + " заболел!");

        List<ScheduleEntry> toNotify = new ArrayList<>(localEntries);
        localEntries.clear();

        notifyAffectedParts(toNotify);
    }

    // Добавление записи
    public void addLocalEntry(ScheduleEntry entry) {
        localEntries.add(entry);
        System.out.println("📝 Рабочий " + worker.getId() + " записал операцию: " +
                entry.getProductId() + "/" + entry.getPartId() + "/" + entry.getOperationId() +
                " [" + entry.getStart() + ".." + entry.getEnd() + "]");
    }

    // Удаление записи
    public boolean removeLocalEntry(String productId, String partId, String operationId) {
        return localEntries.removeIf(e ->
                Objects.equals(e.getProductId(), productId) &&
                        Objects.equals(e.getPartId(), partId) &&
                        Objects.equals(e.getOperationId(), operationId));
    }

    // Уведомление связанных деталей о болезни
    private void notifyAffectedParts(List<ScheduleEntry> entries) {
        if (entries.isEmpty()) {
            System.out.println("ℹ Рабочий " + worker.getId() +
                    " не имеет забронированных операций, уведомлять некого");
            return;
        }

        Set<String> targets = new HashSet<>();
        for (ScheduleEntry e : entries) {
            targets.add("part-" + e.getProductId() + "-" + e.getPartId());
        }

        DFAgentDescription tpl = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("part");
        tpl.addServices(sd);

        DFAgentDescription[] partsList;
        try {
            partsList = DFService.search(this, tpl);
        }
        catch (FIPAException ex) {
            ex.printStackTrace();
            return;
        }

        // Поиск всех связанных деталей и уведомление их
        int sent = 0;
        for (DFAgentDescription d : partsList) {
            if (targets.contains(d.getName().getLocalName())) {
                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(d.getName());
                msg.setConversationId("fault-notify");
                msg.setContent("worker_sick" + ";" + worker.getId());
                send(msg);
                sent++;
            }
        }
        System.out.println("📣 Рабочий " + worker.getId() +
                " уведомил " + sent + " деталей об отказе (болезнь)");
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }
}
