package org.example.behaviours;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.example.agents.MachineAgent;
import org.example.models.ScheduleEntry;

// Поведение планирования станка
public class MachineScheduleBehaviour extends CyclicBehaviour {
    private final MachineAgent agent;

    public MachineScheduleBehaviour(MachineAgent agent) {
        this.agent = agent;
    }

    @Override
    public void action() {
        // Получение сообщений
        MessageTemplate mt = MessageTemplate.MatchConversationId("op-cfp");
        ACLMessage msg = myAgent.receive(mt);
        if (msg == null) {
            block();
            return;
        }

        ACLMessage reply = msg.createReply();

        switch (msg.getPerformative()) {
            case ACLMessage.CFP -> handleCfp(msg, reply);
            case ACLMessage.ACCEPT_PROPOSAL -> handleAccept(msg);
            case ACLMessage.CANCEL -> handleCancel(msg);
            case ACLMessage.QUERY_REF -> handleQuery(msg);
            default -> {
                reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
                myAgent.send(reply);
            }
        }
    }

    // Получение запроса о свободном интервале и цены
    private void handleCfp(ACLMessage msg, ACLMessage reply) {
        String[] parts = msg.getContent().split(";");
        if (parts.length < 4) {
            reply.setPerformative(ACLMessage.NOT_UNDERSTOOD);
            reply.setContent("bad_format");
            myAgent.send(reply);
            return;
        }
        double start = Double.parseDouble(parts[1]);
        double end = Double.parseDouble(parts[2]);
        double dueDate = Double.parseDouble(parts[3]);

        if (agent.isBroken()) {
            refuse(reply, "broken");
            return;
        }
        if (start < agent.getMachine().getWorkStart() || end > agent.getMachine().getWorkEnd()) {
            refuse(reply, "out_of_shift"); return;
        }
        if (!agent.getSchedule().isMachineFree(agent.getMachine().getId(), start, end)) {
            refuse(reply, "busy"); return;
        }

        // Расчет целевой функции
        double f = end + (end > dueDate ? 10.0 * (end - dueDate) : 0.0);
        reply.setPerformative(ACLMessage.PROPOSE);
        reply.setContent(start + ";" + end + ";cost=" + f);
        myAgent.send(reply);
    }

    // Отправка отказа с причиной
    private void refuse(ACLMessage reply, String why) {
        reply.setPerformative(ACLMessage.REFUSE);
        reply.setContent(why);
        myAgent.send(reply);
    }

    // Получение запроса от детали о принятии станка
    private void handleAccept(ACLMessage msg) {
        String[] p = msg.getContent().split(";");
        if (p.length < 7) {
            System.out.println("⚠ Станок " + agent.getMachine().getId() +
                    ": некорректный ACCEPT (нужно 7 полей): " + msg.getContent());
            return;
        }
        ScheduleEntry entry = new ScheduleEntry();
        entry.setStart(Double.parseDouble(p[0]));
        entry.setEnd(Double.parseDouble(p[1]));
        entry.setProductId(p[2]);
        entry.setPartId(p[3]);
        entry.setOperationId(p[4]);
        entry.setMachineId(p[5]);
        entry.setWorkerId(p[6]);
        agent.addLocalEntry(entry);
    }

    // Получение запроса от детали об отмене
    private void handleCancel(ACLMessage msg) {
        String[] p = msg.getContent().split(";");
        if (p.length < 7) {
            System.out.println("🔧 Станок " + agent.getMachine().getId() +
                    " получил CANCEL c неполным содержимым: " + msg.getContent());
            return;
        }
        boolean removed = agent.removeLocalEntry(p[2], p[3], p[4]);
        if (removed) {
            System.out.println("🔧 Станок " + agent.getMachine().getId() +
                    " удалил запись " + p[2] + "/" + p[3] + "/" + p[4] + " по CANCEL");
        }
    }

    // Получение запроса об отправке записей плана
    private void handleQuery(ACLMessage msg) {
        StringBuilder sb = new StringBuilder();
        for (ScheduleEntry e : agent.getLocalEntries()) {
            sb.append(e.getMachineId()).append("|")
                    .append(e.getWorkerId()).append("|")
                    .append(e.getProductId()).append("|")
                    .append(e.getPartId()).append("|")
                    .append(e.getOperationId()).append("|")
                    .append(e.getStart()).append("|")
                    .append(e.getEnd()).append("\n");
        }
        ACLMessage reply = msg.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setContent(sb.toString());
        myAgent.send(reply);
    }
}
