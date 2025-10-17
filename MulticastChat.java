import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;
import java.util.concurrent.*;

/**
 * MulticastChat
 * ---------------------------------------------------------
 * Programa que demuestra el uso de MULTICAST y CONCURRENCIA
 * en sistemas distribuidos, según el plan docente UTPL 2025–2026.
 *
 * Funcionalidades:
 *  - Envío y recepción de mensajes multicast.
 *  - Concurrencia mediante ExecutorService (pool de hilos).
 *  - Selección de interfaz de red (útil en Proxmox, ZeroTier o Hamachi).
 *  - Cierre seguro con hook de apagado.
 *
 * Autor: (Nombre del estudiante)
 * Docente: Rommel Vicente Torres Tandazo, PhD
 * Asignatura: Sistemas Distribuidos
 * ---------------------------------------------------------
 */

public class MulticastChat {

    private static final int MAX_PACKET = 8192;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final InetAddress group;
    private final int port;
    private final String nodeName;
    private final NetworkInterface netIf;

    private final ExecutorService workerPool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                // ✅ Reemplazo de método obsoleto getId()
                t.setName("worker-" + System.identityHashCode(t));
                return t;
            });

    private volatile boolean running = true;

    public MulticastChat(InetAddress group, int port, String nodeName, NetworkInterface netIf) {
        this.group = group;
        this.port = port;
        this.nodeName = nodeName;
        this.netIf = netIf;
    }

    public void start() throws IOException {
        MulticastSocket socket = new MulticastSocket(port);
        socket.setReuseAddress(true);
        socket.setTimeToLive(16); // TTL: 1 local, 16 LAN

        if (netIf != null) socket.setNetworkInterface(netIf);
        socket.joinGroup(new InetSocketAddress(group, port), netIf);

        System.out.printf("[INFO] %s unido a %s:%d en interfaz %s%n",
                nodeName, group.getHostAddress(), port,
                netIf != null ? netIf.getDisplayName() : "<default>");

        Thread receiver = new Thread(() -> receiveLoop(socket));
        receiver.setDaemon(true);
        receiver.setName("receiver");
        receiver.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            try {
                socket.leaveGroup(new InetSocketAddress(group, port), netIf);
            } catch (Exception ignored) { }
            socket.close();
            workerPool.shutdownNow();
            System.out.println("\n[INFO] Finalizado correctamente.");
        }));

        try (Scanner sc = new Scanner(System.in)) {
            System.out.println("[INFO] Escriba mensajes y presione Enter. Use /auto para latidos, /quit para salir.");
            boolean auto = false;
            long lastAuto = 0L;

            while (running) {
                if (auto) {
                    long now = System.currentTimeMillis();
                    if (now - lastAuto >= 2000) {
                        String msg = String.format("heartbeat from %s @ %s", nodeName, TS.format(LocalDateTime.now()));
                        send(socket, msg);
                        lastAuto = now;
                    }
                }

                try {
                    if (System.in.available() > 0) {
                        String line = sc.nextLine().trim();
                        if (line.equalsIgnoreCase("/quit")) break;
                        if (line.equalsIgnoreCase("/auto")) {
                            auto = !auto;
                            System.out.println("[INFO] Modo automático: " + auto);
                            continue;
                        }
                        if (!line.isEmpty()) send(socket, line);
                    } else {
                        Thread.sleep(50);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } // 👈 cierre correcto del bloque try-with-resources

        System.exit(0);
    } // 👈 cierre correcto del método start()

    private void receiveLoop(MulticastSocket socket) {
        byte[] buf = new byte[MAX_PACKET];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (running) {
            try {
                socket.receive(packet);
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                InetAddress srcAddr = packet.getAddress();

                workerPool.submit(() -> processMessage(data, srcAddr));

            } catch (SocketException se) {
                if (running) System.err.println("[WARN] Socket cerrado: " + se.getMessage());
                break;
            } catch (IOException e) {
                if (running) System.err.println("[ERROR] Recibiendo: " + e.getMessage());
            }
        }
    }

    private void processMessage(byte[] data, InetAddress srcAddr) {
        String text = new String(data, StandardCharsets.UTF_8);
        String ts = TS.format(LocalDateTime.now());
        System.out.printf("[%s] <%s> %s%n", ts, srcAddr.getHostAddress(), text);
    }

    private void send(MulticastSocket socket, String body) {
        String payload = nodeName + ": " + body;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        DatagramPacket out = new DatagramPacket(bytes, bytes.length, group, port);
        try {
            socket.send(out);
        } catch (IOException e) {
            System.err.println("[ERROR] Enviando: " + e.getMessage());
        }
    }

    private static void usage() {
        System.out.println("Uso:");
        System.out.println("  java MulticastChat --group 239.1.1.1 --port 5000 --iface <IP> --name \"Nodo-X\"");
        System.out.println("Comandos durante ejecución: /auto (modo latido), /quit (salir)");
    }

    public static void main(String[] args) throws Exception {
        String groupStr = "239.1.1.1";
        int port = 5000;
        String ifaceIp = null;
        String name = "node-" + (int) (Math.random() * 1000);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--group": groupStr = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--iface": ifaceIp = args[++i]; break;
                case "--name": name = args[++i]; break;
                case "-h":
                case "--help": usage(); return;
                default:
                    System.err.println("Argumento no reconocido: " + args[i]);
                    usage(); return;
            }
        }

        System.setProperty("java.net.preferIPv4Stack", "true");

        InetAddress group = InetAddress.getByName(groupStr);
        if (!group.isMulticastAddress()) {
            throw new IllegalArgumentException("La dirección " + groupStr + " no es multicast.");
        }

        NetworkInterface netIf = null;
        if (ifaceIp != null) {
            InetAddress ifAddr = InetAddress.getByName(ifaceIp);
            netIf = NetworkInterface.getByInetAddress(ifAddr);
            if (netIf == null) {
                throw new IllegalArgumentException("No se encontró interfaz con IP " + ifaceIp);
            }
        } else {
            netIf = findFirstUsableMulticastInterface();
            if (netIf == null) {
                throw new IllegalStateException("No se encontró interfaz multicast usable. Use --iface.");
            }
        }

        MulticastChat app = new MulticastChat(group, port, name, netIf);
        app.start();
    }

    private static NetworkInterface findFirstUsableMulticastInterface() throws SocketException {
        for (NetworkInterface nif : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback() || !nif.supportsMulticast()) continue;
            boolean hasV4 = java.util.Collections.list(nif.getInetAddresses())
                    .stream().anyMatch(a -> a instanceof Inet4Address);
            if (hasV4) return nif;
        }
        return null;
    }
}
