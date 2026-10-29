package org.example;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Аргументы для запуска:");
            System.out.println("  server                         — запустить сервер");
            System.out.println("  client <ip> machines           — запустить клиент с агентами станков и рабочих");
            System.out.println("  client <ip> parts              — запустить клиент с агентами изделий/деталей");
            System.out.println("  client <ip> suppliers          — запустить клиент с внешними агентами поставщиков");
            System.out.println("  client <ip> fault <machineId>  — вызвать поломку станка с указанным ID");
            System.out.println("  client <ip> fault <workerId>   — вызвать болезнь рабочего с указанным ID");
            return;
        }

        String mode = args[0];

        if ("server".equalsIgnoreCase(mode)) {
            ServerLauncher.start();
        } else if ("client".equalsIgnoreCase(mode)) {
            if (args.length < 3) {
                System.out.println("Неверный формат команды. Правильно: client <ip> <machines|parts|suppliers|fault>");
                return;
            }
            String host = args[1];
            String role = args[2];
            ClientLauncher.start(host, role, args);
        } else {
            System.out.println("Неизвестный режим: " + mode);
        }
    }
}
