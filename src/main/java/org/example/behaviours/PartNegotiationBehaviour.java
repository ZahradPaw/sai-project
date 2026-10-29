package org.example.behaviours;

import jade.core.AID;
import jade.core.behaviours.Behaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.Property;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.example.agents.PartAgent;
import org.example.models.Operation;
import org.example.models.ScheduleEntry;

import java.util.*;

// Поведение детали: отвечает за то, чтобы каждая операция детали была выполнена
public class PartNegotiationBehaviour extends Behaviour {
    private final PartAgent agent;
    private int opIndex;
    private int step = 0;
    private long stepStart;
    private static final double EPS = 0.001;

    private final List<AID> machines = new ArrayList<>();
    private final List<AID> workers = new ArrayList<>();
    private final Map<AID, String> machineProposals = new HashMap<>();
    private final Map<AID, String> workerProposals = new HashMap<>();
    private int repliesCnt = 0;
    private int expected = 0;
    private boolean supplyChecked = false;

    // Сдвиг стартового окна при неудаче, в минутах
    private static final double WINDOW_SHIFT = 30.0;
    private static final int MAX_SHIFTS = 6;       // до 3 часов сдвига
    private static final int MAX_FAILED_ATTEMPTS = 3;

    private int failedAttempts = 0;
    private int shiftsUsed = 0;

    // Временный сдвиг старта при повторной попытке.
    private double extraOffset = 0.0;

    private AID lockedMachine = null;
    private double lockedStart = 0, lockedEnd = 0;

    public PartNegotiationBehaviour(PartAgent agent) {
        this.agent = agent;
        this.opIndex = agent.getNextOperationIndex();
    }

    @Override
    public void action() {
        // Номер операции
        int desired = agent.getNextOperationIndex();
        if (opIndex != desired) {
            opIndex = desired;
            resetAll();
        }

        // Деталь готова
        if (opIndex >= agent.getPart().getOperations().size()) {
            agent.notifyProductDone();
            return;
        }

        Operation op = agent.getPart().getOperations().get(opIndex);

        switch (step) {
            case 0 -> findResources(op);
            case 1 -> collectProposals();
            case 2 -> chooseMachine(op);
            case 3 -> matchWorker(op);
            case 4 -> finalizeOrRetry(op);
        }
    }

    // Очистка попытки
    private void resetRound() {
        step = 0;
        machines.clear();
        workers.clear();
        machineProposals.clear();
        workerProposals.clear();
        repliesCnt = 0;
        expected = 0;
        lockedMachine = null;
        lockedStart = 0;
        lockedEnd = 0;
    }

    // Обнуление всего состояния
    private void resetAll() {
        resetRound();
        failedAttempts = 0;
        shiftsUsed = 0;
        extraOffset = 0.0;
    }

    // Свойства
    private String getProperty(ServiceDescription sd, String name) {
        Iterator<Property> it = sd.getAllProperties();
        while (it.hasNext()) {
            Property p = it.next();
            if (name.equals(p.getName())) {
                return p.getValue() == null ? null : p.getValue().toString();
            }
        }
        return null;
    }

    // Шаг 1: Поиск станков и рабочих для выполнения операции
    private void findResources(Operation op) {
        try {
            if (!supplyChecked) {
                checkSupply();
                supplyChecked = true;
            }

            machines.clear();
            workers.clear();

            DFAgentDescription tplM = new DFAgentDescription();
            ServiceDescription sdM = new ServiceDescription();
            sdM.setType("machine");
            tplM.addServices(sdM);

            // Поиск нужных станков
            for (DFAgentDescription d : DFService.search(agent, tplM)) {
                Iterator<ServiceDescription> it = d.getAllServices();
                while (it.hasNext()) {
                    ServiceDescription s = it.next();
                    if (op.getRequiredMachineType().equals(getProperty(s, "machineType"))) {
                        machines.add(d.getName());
                        break;
                    }
                }
            }

            DFAgentDescription tplW = new DFAgentDescription();
            ServiceDescription sdW = new ServiceDescription();
            sdW.setType("worker");
            tplW.addServices(sdW);

            // Поиск нужных рабочих
            for (DFAgentDescription d : DFService.search(agent, tplW)) {
                Iterator<ServiceDescription> it = d.getAllServices();
                while (it.hasNext()) {
                    ServiceDescription s = it.next();
                    if (op.getRequiredSkill().equals(getProperty(s, "skill"))) {
                        workers.add(d.getName());
                        break;
                    }
                }
            }

            if (machines.isEmpty() || workers.isEmpty()) {
                System.out.println("⚠ PartAgent " + agent.getPart().getId() +
                        ": нет ресурсов для операции " + op.getId());
                failOrShift(op);
                return;
            }

            // Отправка запроса всем подходящим станкам и рабочим
            double start = agent.getCurrentTime() + extraOffset;
            double end = start + op.getSetupTime() + op.getDuration();
            double dueDate = agent.getDueDate();

            ACLMessage cfpM = new ACLMessage(ACLMessage.CFP);
            machines.forEach(cfpM::addReceiver);
            cfpM.setConversationId("op-cfp");
            cfpM.setContent(agent.getPart().getId() + ";" + start + ";" + end + ";" + dueDate);
            cfpM.setReplyWith("cfpM-" + System.currentTimeMillis());
            agent.send(cfpM);

            ACLMessage cfpW = new ACLMessage(ACLMessage.CFP);
            workers.forEach(cfpW::addReceiver);
            cfpW.setConversationId("op-cfp");
            cfpW.setContent(agent.getPart().getId() + ";" + start + ";" + end + ";" + dueDate
                    + ";" + op.getRequiredSkill());
            cfpW.setReplyWith("cfpW-" + System.currentTimeMillis());
            agent.send(cfpW);

            expected = machines.size() + workers.size();
            repliesCnt = 0;
            stepStart = System.currentTimeMillis();
            step = 1;
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    // Шаг 2: Сбор ответов на запросы ресурсов
    private void collectProposals() {
        MessageTemplate mt = MessageTemplate.MatchConversationId("op-cfp");
        ACLMessage r;
        while ((r = agent.receive(mt)) != null) {
            repliesCnt++;
            if (r.getPerformative() == ACLMessage.PROPOSE) {
                if (isWorker(r.getSender())) workerProposals.put(r.getSender(), r.getContent());
                else machineProposals.put(r.getSender(), r.getContent());
            }
        }
        long TIMEOUT = 4000;
        if (repliesCnt >= expected || System.currentTimeMillis() - stepStart > TIMEOUT) {
            step = 2;
        } else {
            block(200);
        }
    }

    private boolean isWorker(AID aid) {
        return workers.contains(aid);
    }

    // Шаг 3: Выбор станка для операции
    private void chooseMachine(Operation op) {
        AID bestM = null;
        double bestF = Double.MAX_VALUE;
        double mStart = 0, mEnd = 0;

        // Поиск лучшего станка с минимальной стоимостью
        for (var e : machineProposals.entrySet()) {
            double f = parseCost(e.getValue());
            if (f < bestF) {
                bestF = f;
                bestM = e.getKey();
                String[] p = e.getValue().split(";");
                mStart = Double.parseDouble(p[0]);
                mEnd = Double.parseDouble(p[1]);
            }
        }

        if (bestM == null) {
            System.out.println("❌ PartAgent " + agent.getPart().getId() +
                    ": никто из станков не предложил слот для " + op.getId());
            failOrShift(op);
            return;
        }

        lockedMachine = bestM;
        lockedStart = mStart;
        lockedEnd = mEnd;
        System.out.println("🎯 PartAgent " + agent.getPart().getId() +
                ": выбран станок " + bestM.getLocalName() +
                " [" + mStart + ".." + mEnd + "] для " + op.getId());
        step = 3;
    }

    // Шаг 4: Поиск рабочих с интервалами, как у выбранного станка
    private void matchWorker(Operation op) {
        // 1) Пробуем среди уже ответивших
        for (var e : workerProposals.entrySet()) {
            String[] p = e.getValue().split(";");
            double wS = Double.parseDouble(p[0]);
            double wE = Double.parseDouble(p[1]);
            if (Math.abs(wS - lockedStart) < EPS && Math.abs(wE - lockedEnd) < EPS) {
                finalizeSchedule(op, e.getKey(), lockedStart, lockedEnd);
                return;
            }
        }

        // 2) Если никто не ответил — шлём точечный CFP всем рабочим нужной квалификации с интервалом станка
        workerProposals.clear();
        ACLMessage cfpW = new ACLMessage(ACLMessage.CFP);
        for (AID w : workers) cfpW.addReceiver(w);
        cfpW.setConversationId("op-cfp");
        cfpW.setContent(agent.getPart().getId() + ";" + lockedStart + ";" + lockedEnd
                + ";" + agent.getDueDate() + ";" + op.getRequiredSkill());
        cfpW.setReplyWith("cfpW2-" + System.currentTimeMillis());
        agent.send(cfpW);

        block(500);

        // Сбор ответов
        MessageTemplate mt = MessageTemplate.MatchConversationId("op-cfp");
        ACLMessage r;
        while ((r = agent.receive(mt)) != null) {
            if (r.getPerformative() == ACLMessage.PROPOSE && isWorker(r.getSender())) {
                workerProposals.put(r.getSender(), r.getContent());
            }
        }

        step = 4;
    }

    // Последний шаг
    private void finalizeOrRetry(Operation op) {
        // Если есть рабочий, чей интервал совпадает с интервалом станка
        for (var e : workerProposals.entrySet()) {
            String[] p = e.getValue().split(";");
            double wS = Double.parseDouble(p[0]);
            double wE = Double.parseDouble(p[1]);
            if (Math.abs(wS - lockedStart) < EPS && Math.abs(wE - lockedEnd) < EPS) {
                finalizeSchedule(op, e.getKey(), lockedStart, lockedEnd);
                return;
            }
        }

        System.out.println("❌ PartAgent " + agent.getPart().getId() +
                ": не удалось подобрать согласованную пару станок+рабочий для " + op.getId() +
                " (интервал " + lockedStart + ".." + lockedEnd + ")");
        failOrShift(op);
    }

    // Формирование итоговой записи в план
    private void finalizeSchedule(Operation op, AID worker, double start, double end) {
        String machineId = lockedMachine.getLocalName().replace("machine-", "");
        String workerId  = worker.getLocalName().replace("worker-", "");

        ScheduleEntry entry = new ScheduleEntry();
        entry.setMachineId(machineId);
        entry.setWorkerId(workerId);
        entry.setProductId(agent.getProduct().getId());
        entry.setPartId(agent.getPart().getId());
        entry.setOperationId(op.getId());
        entry.setStart(start);
        entry.setEnd(end);

        agent.getSchedule().book(machineId, workerId, entry);
        agent.addPlannedEntry(entry);
        agent.setNextOperationIndex(opIndex + 1);
        agent.setCurrentTime(end);

        System.out.println("📌 PartAgent " + agent.getPart().getId() +
                ": операция " + op.getId() + " запланирована [" +
                start + ".." + end + "] на " + machineId + " + " + workerId);

        sendConfirm(lockedMachine, entry);
        sendConfirm(worker, entry);

        opIndex++;
        failedAttempts = 0;
        shiftsUsed = 0;
        extraOffset = 0.0;
        resetRound();
    }

    // Сдвиг окна или пропуск операции при неудачном поиске
    private void failOrShift(Operation op) {
        failedAttempts++;

        if (failedAttempts < MAX_FAILED_ATTEMPTS) {
            System.out.println("↻ PartAgent " + agent.getPart().getId() +
                    ": попытка " + failedAttempts + "/" + MAX_FAILED_ATTEMPTS +
                    " для " + op.getId() + ", повтор через паузу");
            resetRound();
            block(1500);
            return;
        }

        // Если все попытки в окне закончились, то сдвигаем окно и пробуем ещё
        if (shiftsUsed < MAX_SHIFTS) {
            shiftsUsed++;
            failedAttempts = 0;
            extraOffset = shiftsUsed * WINDOW_SHIFT;
            System.out.println("⏭ PartAgent " + agent.getPart().getId() +
                    ": сдвигаем окно операции " + op.getId() +
                    " на +" + extraOffset + " мин (сдвиг " + shiftsUsed + "/" + MAX_SHIFTS + ")");
            resetRound();
            block(800);
            return;
        }

        System.out.println("⛔ PartAgent " + agent.getPart().getId() +
                ": операция " + op.getId() +
                " не может быть запланирована. Пропускаем.");
        agent.markOperationSkipped();
        opIndex++;
        failedAttempts = 0;
        shiftsUsed = 0;
        extraOffset = 0.0;
        resetRound();

        // Если это была последняя операция, то сообщаем изделию, что деталь не завершена
        if (opIndex >= agent.getPart().getOperations().size()) {
            agent.notifyProductDone();
        }
    }

    // Проверка наличия заготовки у поставщика
    private void checkSupply() {
        try {
            DFAgentDescription tpl = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType("supplier");
            tpl.addServices(sd);
            DFAgentDescription[] sups = DFService.search(agent, tpl);
            if (sups.length == 0) {
                System.out.println("ℹ PartAgent " + agent.getPart().getId() +
                        ": поставщики не найдены, планируем без проверки заготовки");
                return;
            }
            ACLMessage ask = new ACLMessage(ACLMessage.REQUEST);
            ask.addReceiver(sups[0].getName());
            ask.setConversationId("supply-check");
            ask.setContent(agent.getPart().getId());
            agent.send(ask);

            ACLMessage ans = agent.blockingReceive(
                    MessageTemplate.and(
                            MessageTemplate.MatchConversationId("supply-check"),
                            MessageTemplate.MatchSender(sups[0].getName())),
                    3000);
            if (ans != null && ans.getPerformative() == ACLMessage.INFORM) {
                System.out.println("✔ PartAgent " + agent.getPart().getId() +
                        ": заготовка есть у " + sups[0].getName().getLocalName());
            } else {
                System.out.println("⚠ PartAgent " + agent.getPart().getId() +
                        ": заготовки нет у поставщика (продолжаем планирование)");
            }
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    // Парсинг ответа от станка/рабочего
    private double parseCost(String content) {
        int i = content.indexOf("cost=");
        if (i < 0) {
            try {
                String[] parts = content.split(";");
                return Double.parseDouble(parts[1]) - Double.parseDouble(parts[0]);
            } catch (Exception ex) {
                return Double.MAX_VALUE;
            }
        }
        return Double.parseDouble(content.substring(i + 5));
    }

    // Отправка подтверждения бронирования указанному получателю
    private void sendConfirm(AID target, ScheduleEntry entry) {
        ACLMessage m = new ACLMessage(ACLMessage.ACCEPT_PROPOSAL);
        m.addReceiver(target);
        m.setConversationId("op-cfp");
        m.setContent(entry.getStart() + ";" + entry.getEnd()
                + ";" + entry.getProductId()
                + ";" + entry.getPartId()
                + ";" + entry.getOperationId()
                + ";" + entry.getMachineId()
                + ";" + entry.getWorkerId());
        agent.send(m);
    }

    @Override
    public boolean done() {
        // Конец всех операций
        return opIndex >= agent.getPart().getOperations().size();
    }
}
