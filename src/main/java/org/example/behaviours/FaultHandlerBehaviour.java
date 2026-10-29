package org.example.behaviours;

import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.example.agents.MachineAgent;
import org.example.agents.WorkerAgent;

// Поведение обработки аварийных ситуаций (болезнь рабочего / поломка станка)
public class FaultHandlerBehaviour extends CyclicBehaviour {
    private final String kind;  // тип агента

    public FaultHandlerBehaviour(jade.core.Agent agent, String kind) {
        super(agent);
        this.kind = kind;
    }

    @Override
    public void action() {
        MessageTemplate mt = MessageTemplate.MatchConversationId("fault");
        ACLMessage msg = myAgent.receive(mt);
        if (msg == null) {
            block();
            return;
        }

        // Вызов поломки станка или болезни рабочего в зависимости от сообщения
        if ("machine".equals(kind) && myAgent instanceof MachineAgent ma) {
            ma.breakDown();
        } else if ("worker".equals(kind) && myAgent instanceof WorkerAgent wa) {
            wa.makeSick();
        }
    }
}
