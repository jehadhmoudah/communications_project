import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.time.LocalTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class P2PChat extends JFrame {

    private DefaultListModel<String> chatModel = new DefaultListModel<>();
    private JList<String> chatList;
    private DefaultListModel<String> archiveModel = new DefaultListModel<>();
    private JList<String> archiveList;

    private JTextField messageField, localPortField, remotePortField, localIPField, remoteIPField;
    private JButton sendButton, deleteButton, deleteAllButton, restoreButton;
    private DatagramSocket socket;

    private DefaultListModel<String> onlineListModel;
    private JList<String> onlineUsers;
    private final Map<String, Long> peerTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Long> archiveTimestamps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private final int BROADCAST_PORT = 8888;
    private final int TIMEOUT_MS = 5000;

    public P2PChat() {
        setTitle("P2P Chat");
        setSize(800, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(null);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBounds(10, 10, 470, 300);

        chatList = new JList<>(chatModel);
        chatList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value);
            label.setOpaque(true);
            label.setFont(new Font("Monospaced", Font.PLAIN, 12));
            label.setBackground(isSelected ? Color.LIGHT_GRAY : Color.WHITE);

            if (value.startsWith("Me:")) {
                label.setForeground(Color.YELLOW.darker());
            } else if (value.startsWith("Rem:")) {
                label.setForeground(Color.ORANGE.darker());
            } else if (value.startsWith("[Sender]") || value.contains("Error")) {
                label.setForeground(Color.RED);
            } else if (value.startsWith("[Responder]") || value.startsWith("[Receiver]")) {
                label.setForeground(Color.GRAY);
            } else {
                label.setForeground(Color.BLACK);
            }

            return label;
        });

        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabs.addTab("Chat", new JScrollPane(chatList));

        archiveList = new JList<>(archiveModel);
        archiveList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabs.addTab("Archive", new JScrollPane(archiveList));

        add(tabs);

        messageField = new JTextField();
        messageField.setBounds(10, 320, 470, 30);
        add(messageField);

        sendButton = new JButton("Send");
        sendButton.setBounds(490, 320, 80, 30);
        add(sendButton);

        deleteButton = new JButton("Delete");
        deleteButton.setBounds(580, 320, 80, 30);
        add(deleteButton);

        deleteAllButton = new JButton("Delete All");
        deleteAllButton.setBounds(670, 320, 110, 30);
        add(deleteAllButton);

        restoreButton = new JButton("Restore");
        restoreButton.setBounds(490, 360, 290, 30);
        add(restoreButton);

        String localIP = getLocalIPv4();
        int autoPort = getAvailablePort();

        localIPField = new JTextField(localIP);
        localPortField = new JTextField(String.valueOf(autoPort));
        remoteIPField = new JTextField();
        remotePortField = new JTextField();

        addLabeledField("Local IP:", localIPField, 490, 10);
        addLabeledField("Local Port:", localPortField, 490, 40);
        addLabeledField("Remote IP:", remoteIPField, 490, 70);
        addLabeledField("Remote Port:", remotePortField, 490, 100);

        onlineListModel = new DefaultListModel<>();
        onlineUsers = new JList<>(onlineListModel);
        JScrollPane onlineScroll = new JScrollPane(onlineUsers);
        onlineScroll.setBounds(490, 140, 280, 130);
        add(onlineScroll);

        sendButton.addActionListener(e -> sendMessage());
        deleteButton.addActionListener(e -> deleteSelectedMessage());
        deleteAllButton.addActionListener(e -> deleteAllMessages());
        restoreButton.addActionListener(e -> restoreMessages());

        onlineUsers.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = onlineUsers.getSelectedValue();
                if (selected == null || !selected.contains(":")) return;
                String[] parts = selected.split(":");
                remoteIPField.setText(parts[0].trim());
                remotePortField.setText(parts[1].trim());
            }
        });

        SwingUtilities.invokeLater(this::startHeartbeatSender);
        SwingUtilities.invokeLater(this::startDiscoveryReceiver);
        SwingUtilities.invokeLater(this::startReceiver);
        scheduler.scheduleAtFixedRate(this::purgeExpiredArchives, 0, 10, TimeUnit.SECONDS);
    }

    private void addLabeledField(String label, JTextField field, int x, int y) {
        add(new JLabel(label)).setBounds(x, y, 80, 20);
        field.setBounds(x + 80, y, 180, 20);
        add(field);
    }

    private void sendMessage() {
        try {
            String msg = messageField.getText().trim();
            String remoteIP = remoteIPField.getText().trim();
            String remotePortText = remotePortField.getText().trim();
            if (remoteIP.isEmpty() || remotePortText.isEmpty()) {
                chatModel.addElement("[Sender] Please select a device from the list.");
                return;
            }
            int remotePort = Integer.parseInt(remotePortText);
            byte[] data = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(remoteIP), remotePort);
            socket.send(packet);
            String formatted = String.format("Me: %s [%s]", msg, LocalTime.now().withNano(0));
            chatModel.addElement(formatted);
            messageField.setText("");
        } catch (Exception e) {
            chatModel.addElement(String.format("[Sender] Error: %s", e.getMessage()));
        }
    }

    private void deleteSelectedMessage() {
        int index = chatList.getSelectedIndex();
        if (index != -1) {
            String msg = chatModel.remove(index);
            archiveModel.addElement(msg);
            archiveTimestamps.put(msg, System.currentTimeMillis());
        }
    }

    private void deleteAllMessages() {
        for (int i = 0; i < chatModel.size(); i++) {
            String msg = chatModel.get(i);
            archiveModel.addElement(msg);
            archiveTimestamps.put(msg, System.currentTimeMillis());
        }
        chatModel.clear();
    }

    private void restoreMessages() {
        for (int i = 0; i < archiveModel.size(); i++) {
            chatModel.addElement(archiveModel.get(i));
        }
        archiveModel.clear();
        archiveTimestamps.clear();
    }

    private void purgeExpiredArchives() {
        long now = System.currentTimeMillis();
        Iterator<String> it = archiveModel.elements().asIterator();
        while (it.hasNext()) {
            String msg = it.next();
            if (now - archiveTimestamps.getOrDefault(msg, 0L) > 120_000) {
                archiveModel.removeElement(msg);
                archiveTimestamps.remove(msg);
            }
        }
    }

    private void startHeartbeatSender() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                while (true) {
                    String msg = "P2PCHAT_HERE:" + localPortField.getText().trim();
                    byte[] data = msg.getBytes();
                    DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), BROADCAST_PORT);
                    socket.send(packet);
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void startDiscoveryReceiver() {
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(BROADCAST_PORT, InetAddress.getByName("0.0.0.0"))) {
                socket.setBroadcast(true);
                byte[] buffer = new byte[1500];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());
                    if (msg.startsWith("P2PCHAT_HERE:")) {
                        String port = msg.split(":")[1];
                        String peer = String.format("%s:%s", packet.getAddress().getHostAddress(), port);
                        String self = String.format("%s:%s", localIPField.getText().trim(), localPortField.getText().trim());
                        if (!peer.equals(self) && !onlineListModel.contains(peer)) {
                            onlineListModel.addElement(peer);
                        }
                        peerTimestamps.put(peer, System.currentTimeMillis());
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void startReceiver() {
        new Thread(() -> {
            try {
                socket = new DatagramSocket(Integer.parseInt(localPortField.getText()));
                byte[] buffer = new byte[1024];
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength());
                    String formatted = String.format("Rem: %s [%s, %s:%d]", msg, LocalTime.now().withNano(0), packet.getAddress().getHostAddress(), packet.getPort());
                    chatModel.addElement(formatted);
                }
            } catch (Exception e) {
                chatModel.addElement("[Receiver Error] " + e.getMessage());
            }
        }).start();
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
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private int getAvailablePort() {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            return 5000;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new P2PChat().setVisible(true));
    }
}