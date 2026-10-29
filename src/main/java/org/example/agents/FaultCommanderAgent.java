package org.example.agents;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;

// Агент вызова аварийной ситуации
public class FaultCommanderAgent extends Agent {
    @Override
    protected void setup() {
        Object[] args = getArguments();
        if (args == null || args.length < 2) {
            System.out.println("⚠ FaultCommanderAgent: нужны 2 аргумента");
            doDelete();
            return;
        }
        String target = (String) args[0];   // "machine-M1" или "worker-W1"
        String action = (String) args[1];   // "break" / "repair" / "sick" / "recover"

        ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
        msg.addReceiver(new AID(target, AID.ISLOCALNAME));
        msg.setConversationId("fault");
        msg.setContent(action);
        send(msg);

        System.out.println("📨 FaultCommanderAgent: " + target);

        // Даём время доставить сообщение, затем уничтожаем агента и процесс
        try {
            Thread.sleep(500);
        }
        catch (InterruptedException ignored) {}
        doDelete();
        try {
            Thread.sleep(200);
        }
        catch (InterruptedException ignored) {}

        System.out.println("👋 FaultCommanderAgent завершил работу");
        System.exit(0);
    }
}
