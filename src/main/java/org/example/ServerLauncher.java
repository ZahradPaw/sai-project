package org.example;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;

// Запуск сервера
public class ServerLauncher {
    static final String SERVER_PORT = "12345";

    public static void start() {
        Runtime rt = Runtime.instance();
        Profile p = new ProfileImpl();
        p.setParameter(Profile.MAIN_HOST, "0.0.0.0");
        p.setParameter(Profile.MAIN_PORT, SERVER_PORT);
        p.setParameter(Profile.GUI, "true");
        p.setParameter(Profile.LOCAL_HOST, "localhost");
        AgentContainer mc = rt.createMainContainer(p);
        System.out.println("🚀 Сервер JADE запущен на порту " + SERVER_PORT + ".");
    }
}
