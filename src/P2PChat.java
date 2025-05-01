import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class P2PChat extends JFrame {

    private JTextPane chatPane;
    private JTextPane archivePane;
    private JTextField messageField, localPortField, remotePortField, localIPField, remoteIPField;
    private JButton sendButton, testButton, deleteButton, deleteAllButton, restoreButton;
    private JLabel statusLabel;
    private DatagramSocket socket;

    private DefaultListModel<String> onlineListModel;
    private JList<String> onlineUsers;
    private final Map<String, Long> peerTimestamps = new ConcurrentHashMap<>();
    private final List<ArchivedMessage> archive = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final int BROADCAST_PORT = 8888;
    private final int TIMEOUT_MS = 5000;
    private final File logFile = new File("p2pchat.log");

    public P2PChat() {
        setTitle("P2P Chat");
        setSize(800, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBounds(10, 10, 470, 300);

        chatPane = new JTextPane();
        chatPane.setEditable(false);
        JScrollPane chatScroll = new JScrollPane(chatPane);
        tabs.addTab("Chat", chatScroll);

        archivePane = new JTextPane();
        archivePane.setEditable(false);
        JScrollPane archiveScroll = new JScrollPane(archivePane);
        tabs.addTab("Archive", archiveScroll);

        add(tabs);

        messageField = new JTextField();
        messageField.setBounds(10, 320, 470, 30);
        add(messageField);

        sendButton = new JButton("Send");
        sendButton.setBounds(490, 320, 80, 30);
        add(sendButton);

        testButton = new JButton("Test");
        testButton.setBounds(580, 320, 80, 30);
        add(testButton);

        deleteButton = new JButton("Delete");
        deleteButton.setBounds(670, 320, 100, 30);
        add(deleteButton);

        deleteAllButton = new JButton("Delete All");
        deleteAllButton.setBounds(490, 360, 130, 30);
        add(deleteAllButton);

        restoreButton = new JButton("Restore");
        restoreButton.setBounds(630, 360, 140, 30);
        add(restoreButton);
        String localIP = getLocalIPv4();
        int autoPort = getAvailablePort();

        add(new JLabel("Local IP:")).setBounds(490, 10, 80, 20);
        localIPField = new JTextField(localIP);
        localIPField.setBounds(570, 10, 200, 20);
        add(localIPField);

        add(new JLabel("Local Port:")).setBounds(490, 40, 80, 20);
        localPortField = new JTextField(String.valueOf(autoPort));
        localPortField.setBounds(570, 40, 200, 20);
        add(localPortField);

        add(new JLabel("Remote IP:")).setBounds(490, 70, 80, 20);
        remoteIPField = new JTextField("");
        remoteIPField.setBounds(570, 70, 200, 20);
        add(remoteIPField);

        add(new JLabel("Remote Port:")).setBounds(490, 100, 80, 20);
        remotePortField = new JTextField("");
        remotePortField.setBounds(570, 100, 200, 20);
        add(remotePortField);

        onlineListModel = new DefaultListModel<>();
        onlineUsers = new JList<>(onlineListModel);
        JScrollPane onlineScroll = new JScrollPane(onlineUsers);
        onlineScroll.setBounds(490, 140, 280, 130);
        add(onlineScroll);

        statusLabel = new JLabel("Ready");
        statusLabel.setBounds(10, 360, 470, 20);
        add(statusLabel);

        sendButton.addActionListener(e -> sendMessage());

        testButton.addActionListener(e -> {
            messageField.setText("Test Message");
            sendMessage();
        });

        deleteButton.addActionListener(e -> archiveLastMessage());

        deleteAllButton.addActionListener(e -> archiveAllMessages());

        restoreButton.addActionListener(e -> restoreMessages());

        onlineUsers.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = onlineUsers.getSelectedValue();
                if (selected == null || !selected.contains(":")) return;
                String[] parts = selected.split(":");
                String selectedIP = parts[0].trim();
                String selectedPort = parts[1].trim();

                if (selectedIP.equals(localIPField.getText().trim()) && selectedPort.equals(localPortField.getText().trim())) {
                    log("[UI] Ignored self-selection: " + selected);
                    return;
                }

                remoteIPField.setText(selectedIP);
                remotePortField.setText(selectedPort);
            }
        });

        SwingUtilities.invokeLater(this::startDiscoveryReceiver);
        SwingUtilities.invokeLater(this::startReceiver);
        SwingUtilities.invokeLater(this::startHeartbeatSender);
        SwingUtilities.invokeLater(this::startPeerTimeoutChecker);
        scheduler.scheduleAtFixedRate(this::autoPurgeArchive, 0, 30, TimeUnit.SECONDS);
    }
    private void startPeerTimeoutChecker() {
        new Thread(() -> {
            while (true) {
                try {
                    long now = System.currentTimeMillis();
                    for (String peer : new ArrayList<>(peerTimestamps.keySet())) {
                        if (now - peerTimestamps.get(peer) > TIMEOUT_MS) {
                            peerTimestamps.remove(peer);
                            SwingUtilities.invokeLater(() -> {
                                onlineListModel.removeElement(peer);
                                appendToChat("[Timeout] Removed peer: " + peer + "\n", Color.GRAY);
                            });
                        }
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void sendMessage() {
        try {
            String msg = messageField.getText();
            String remoteIP = remoteIPField.getText().trim();
            String remotePortText = remotePortField.getText().trim();

            if (remoteIP.isEmpty() || remotePortText.isEmpty()) {
                appendToChat("[Sender] Please select a device from the online list.\n", Color.RED);
                return;
            }

            int remotePort = Integer.parseInt(remotePortText);
            byte[] data = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    InetAddress.getByName(remoteIP), remotePort);
            socket.send(packet);

            appendToChat("Me: " + msg + " [" + LocalTime.now().withNano(0) + "]\n", Color.YELLOW);
            log("[Sent] " + msg);
            messageField.setText("");
        } catch (Exception ex) {
            appendToChat("[Sender] Error: " + ex.getMessage() + "\n", Color.RED);
        }
    }

    private void startHeartbeatSender() {
        new Thread(() -> {
            try {
                DatagramSocket broadcastSocket = new DatagramSocket();
                broadcastSocket.setBroadcast(true);
                while (true) {
                    String localPort = localPortField.getText().trim();
                    String message = "P2PCHAT_HERE:" + localPort;
                    byte[] data = message.getBytes();
                    DatagramPacket packet = new DatagramPacket(data, data.length,
                            InetAddress.getByName("255.255.255.255"), BROADCAST_PORT);
                    broadcastSocket.send(packet);
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                appendToChat("[Heartbeat] Error: " + e.getMessage() + "\n", Color.RED);
            }
        }).start();
    }

    private void startDiscoveryReceiver() {
        new Thread(() -> {
            try (DatagramSocket listenSocket = new DatagramSocket(BROADCAST_PORT, InetAddress.getByName("0.0.0.0"))) {
                listenSocket.setBroadcast(true);
                appendToChat("[Responder] Listening on " + BROADCAST_PORT + "...\n", Color.GRAY);
                byte[] buffer = new byte[1500];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    listenSocket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());
                    String senderIP = packet.getAddress().getHostAddress();
                    String localIP = localIPField.getText().trim();
                    String localPort = localPortField.getText().trim();
                    String self = localIP + ":" + localPort;

                    if (msg.startsWith("P2PCHAT_HERE:")) {
                        String theirPort = msg.split(":")[1];
                        String peer = senderIP + ":" + theirPort;

                        if (!peer.equals(self)) {
                            peerTimestamps.put(peer, System.currentTimeMillis());
                            if (!onlineListModel.contains(peer)) {
                                SwingUtilities.invokeLater(() -> {
                                    onlineListModel.addElement(peer);
                                    appendToChat("[Responder] Added peer: " + peer + "\n", Color.GRAY);
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                appendToChat("[Responder] Error: " + e.getMessage() + "\n", Color.RED);
            }
        }).start();
    }
    private void startReceiver() {
        new Thread(() -> {
            try {
                int localPort = Integer.parseInt(localPortField.getText());
                socket = new DatagramSocket(localPort);
                appendToChat("[Receiver] Listening on port " + localPort + "\n", Color.GRAY);

                byte[] buffer = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());

                    String line = "Rem: " + msg + " [" + LocalTime.now().withNano(0) + ", " +
                            packet.getAddress().getHostAddress() + ":" + packet.getPort() + "]\n";

                    appendToChat(line, Color.ORANGE);
                    log("[Received] " + msg);
                }
            } catch (Exception ex) {
                appendToChat("[Receiver] Error: " + ex.getMessage() + "\n", Color.RED);
            }
        }).start();
    }

    private void archiveLastMessage() {
        try {
            StyledDocument doc = chatPane.getStyledDocument();
            int len = doc.getLength();
            if (len == 0) return;

            int start = Utilities.getRowStart(chatPane, len - 1);
            String line = doc.getText(start, len - start);
            doc.remove(start, len - start);
            archive.add(new ArchivedMessage(line, System.currentTimeMillis()));
            archivePane.setText(archivePane.getText() + line);
            log("[Archive] " + line.trim());
        } catch (Exception e) {
            appendToChat("[Archive] Error: " + e.getMessage() + "\n", Color.RED);
        }
    }

    private void archiveAllMessages() {
        try {
            String all = chatPane.getText();
            if (!all.isBlank()) {
                archive.add(new ArchivedMessage(all, System.currentTimeMillis()));
                archivePane.setText(archivePane.getText() + all);
                chatPane.setText("");
                log("[Archive All]");
            }
        } catch (Exception e) {
            appendToChat("[Archive All] Error: " + e.getMessage() + "\n", Color.RED);
        }
    }

    private void restoreMessages() {
        for (ArchivedMessage msg : archive) {
            appendToChat(msg.content, Color.MAGENTA);
        }
        archive.clear();
        archivePane.setText("");
        log("[Restore] All archived messages restored.");
    }

    private void autoPurgeArchive() {
        long now = System.currentTimeMillis();
        archive.removeIf(msg -> (now - msg.timestamp) > 120_000);
        archivePane.setText("");  // Optional: refresh view
        for (ArchivedMessage msg : archive) {
            archivePane.setText(archivePane.getText() + msg.content);
        }
    }

    private void appendToChat(String text, Color color) {
        StyledDocument doc = chatPane.getStyledDocument();
        Style style = chatPane.addStyle("Style", null);
        StyleConstants.setForeground(style, color);
        try {
            doc.insertString(doc.getLength(), text, style);
        } catch (BadLocationException ignored) {}
    }

    private void log(String message) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
            writer.write(LocalTime.now().withNano(0) + " - " + message + "\n");
        } catch (IOException e) {
            System.err.println("Log error: " + e.getMessage());
        }
    }

    private String getLocalIPv4() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    private int getAvailablePort() {
        try (DatagramSocket tempSocket = new DatagramSocket(0)) {
            return tempSocket.getLocalPort();
        } catch (IOException e) {
            e.printStackTrace();
            return 5000;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new P2PChat().setVisible(true));
    }

    private static class ArchivedMessage {
        String content;
        long timestamp;
        public ArchivedMessage(String content, long timestamp) {
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
