package org.example.agents;

import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import lombok.Getter;
import org.example.behaviours.FaultHandlerBehaviour;
import org.example.behaviours.MachineScheduleBehaviour;
import org.example.models.Machine;
import org.example.models.ScheduleEntry;
import org.example.repository.ScheduleRepository;

import java.util.*;

// Агент станка
public class MachineAgent extends Agent {
    @Getter private Machine machine;
    @Getter private ScheduleRepository schedule;
    @Getter private final List<ScheduleEntry> localEntries = new ArrayList<>();
    @Getter private volatile boolean broken = false;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 2) {
            doDelete();
            return;
        }
        this.machine = (Machine) args[0];
        this.schedule = (ScheduleRepository) args[1];

        register();
        addBehaviour(new MachineScheduleBehaviour(this));
        addBehaviour(new FaultHandlerBehaviour(this, "machine"));

        System.out.println("🔧 MachineAgent " + machine.getId() + " (" + machine.getType() + ") запущен");
    }

    // Регистрация агента
    private void register() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("machine");
        sd.setName("machine-" + machine.getId());
        sd.addProperties(new jade.domain.FIPAAgentManagement.Property("id", machine.getId()));
        sd.addProperties(new jade.domain.FIPAAgentManagement.Property("machineType", machine.getType()));
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (FIPAException e) { e.printStackTrace(); }
    }

    // Поломка станка
    public void breakDown() {
        broken = true;
        System.out.println("💥 Станок " + machine.getId() + " сломался!");

        // Обновление плана
        List<ScheduleEntry> toNotify = new ArrayList<>(localEntries);
        localEntries.clear();

        notifyAffectedParts(toNotify);
    }

    // Добавление записи операции
    public void addLocalEntry(ScheduleEntry entry) {
        localEntries.add(entry);
        System.out.println("📝 Станок " + machine.getId() + " записал операцию: " +
                entry.getProductId() + "/" + entry.getPartId() + "/" + entry.getOperationId() +
                " [" + entry.getStart() + ".." + entry.getEnd() + "]");
    }

    // Удаление записи операции
    public boolean removeLocalEntry(String productId, String partId, String operationId) {
        return localEntries.removeIf(e ->
                Objects.equals(e.getProductId(), productId) &&
                        Objects.equals(e.getPartId(), partId) &&
                        Objects.equals(e.getOperationId(), operationId));
    }

    // Уведомление связанных деталей о поломке
    private void notifyAffectedParts(List<ScheduleEntry> entries) {
        if (entries.isEmpty()) {
            System.out.println("ℹ Станок " + machine.getId() +
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
        try { partsList = DFService.search(this, tpl); }
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
                msg.setContent("machine_broken" + ";" + machine.getId());
                send(msg);
                sent++;
            }
        }
        System.out.println("📣 Станок " + machine.getId() +
                " уведомил " + sent + " деталей об отказе (поломка)");
    }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        } catch (FIPAException ignored) {}
    }
}
