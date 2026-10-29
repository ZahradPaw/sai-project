package org.example.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import lombok.Getter;
import org.example.models.*;
import org.example.repository.ScheduleRepository;

import java.util.List;

// Агент изделия
public class ProductAgent extends Agent {
    @Getter private Product product;
    @Getter private List<Part> parts;
    @Getter private ScheduleRepository schedule;

    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 3) {
            doDelete();
            return;
        }
        this.product = (Product) args[0];
        this.parts = (List<Part>) args[1];
        this.schedule = (ScheduleRepository) args[2];

        register();
        System.out.println("📦 ProductAgent " + product.getId() +
                " (срок " + product.getDueDate() + " мин) запущен");

        // Создание агентов деталей для изделия
        for (Part part : parts) {
            try {
                Object[] partArgs = new Object[]{part, product, product.getDueDate(), schedule};
                String agentLocalName = "part-" + product.getId() + "-" + part.getId();
                jade.wrapper.AgentController ac = getContainerController().createNewAgent(
                        agentLocalName,
                        PartAgent.class.getName(),
                        partArgs
                );
                ac.start();
                Thread.sleep(100);
            }
            catch (Exception e) {
                System.out.println("⚠ Не удалось создать агента детали " + part.getId() +
                        ": " + e.getMessage());
            }
        }

        final int total = parts.size();
        final int[] doneOk = {0};
        final int[] doneFailed = {0};

        // Поведение счетчика о готовности изделия
        addBehaviour(new CyclicBehaviour(this) {
            @Override public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchConversationId("part-done"),
                        MessageTemplate.MatchPerformative(ACLMessage.INFORM));
                ACLMessage msg = myAgent.receive(mt);
                if (msg == null) {
                    block();
                    return;
                }

                String[] p = msg.getContent().split(";");
                String partId = p[0];
                String status = p.length > 1 ? p[1] : "OK";

                if ("OK".equals(status)) {
                    doneOk[0]++;
                    System.out.println("✅ ProductAgent " + getProduct().getId() +
                            ": деталь " + partId + " готова (" +
                            (doneOk[0] + doneFailed[0]) + "/" + total + ")");
                } else {
                    doneFailed[0]++;
                    System.out.println("⚠ ProductAgent " + getProduct().getId() +
                            ": деталь " + partId + " НЕ ЗАВЕРШЕНА (" +
                            (doneOk[0] + doneFailed[0]) + "/" + total + ")");
                }

                int finished = doneOk[0] + doneFailed[0];
                if (finished >= total) {
                    if (doneFailed[0] == 0) {
                        System.out.println("🎉 ProductAgent " + getProduct().getId() +
                                " — ИЗДЕЛИЕ ГОТОВО к " + getDueDate() + " мин");
                    } else {
                        System.out.println("🚫 ProductAgent " + getProduct().getId() +
                                " — ИЗДЕЛИЕ НЕ ИЗГОТОВЛЕНО (" +
                                doneFailed[0] + " из " + total +
                                " деталей не завершены)");
                    }
                }
            }
        });

        // Поведение ответа на запрос о дате выполнения
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.and(
                        MessageTemplate.MatchConversationId("due-date"),
                        MessageTemplate.MatchPerformative(ACLMessage.QUERY_REF));
                ACLMessage q = myAgent.receive(mt);
                if (q == null) {
                    block();
                    return;
                }
                ACLMessage r = q.createReply();
                r.setPerformative(ACLMessage.INFORM);
                r.setContent(getProduct().getId() + ";" + getDueDate());
                myAgent.send(r);
            }
        });
    }

    // Регистрация
    private void register() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("product");
        sd.setName("product-" + product.getId());
        dfd.addServices(sd);
        try { DFService.register(this, dfd); } catch (FIPAException e) { e.printStackTrace(); }
    }

    // Срок выполнения
    public double getDueDate() { return product.getDueDate(); }

    @Override
    protected void takeDown() {
        try {
            DFService.deregister(this);
        }
        catch (FIPAException ignored) {}
    }
}
