package org.example.agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Random;


// Агент поставщика из внешнего МАС
public class SupplierAgent extends Agent {
    private final Random rnd = new Random();

    @Override
    protected void setup() {
        Object[] args = getArguments();
        String supplierId = (args != null && args.length > 0) ? (String) args[0] : "supplier-1";

        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType("supplier");
        sd.setName(supplierId);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }

        System.out.println("🏭 SupplierAgent " + supplierId + " запущен");

        // Поведение на ответ запроса о наличии детали
        addBehaviour(new CyclicBehaviour(this) {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchConversationId("supply-check");
                ACLMessage msg = receive(mt);
                if (msg == null) {
                    block();
                    return;
                }

                String partId = msg.getContent();
                boolean hasStock = rnd.nextDouble() > 0.2; // 80% — есть на складе
                ACLMessage reply = msg.createReply();
                if (hasStock) {
                    reply.setPerformative(ACLMessage.INFORM);
                    reply.setContent("IN_STOCK");
                } else {
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("OUT_OF_STOCK");
                }
                send(reply);
                System.out.println("🏭 SupplierAgent: запрос на " + partId +
                        " -> " + (hasStock ? "ЕСТЬ" : "НЕТ"));
            }
        });
    }

    @Override
    protected void takeDown() {
        try { DFService.deregister(this); } catch (FIPAException ignored) {}
    }
}
