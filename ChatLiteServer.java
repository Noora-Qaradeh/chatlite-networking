import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatLiteServer extends Application {

    private final String CONTROL_STYLE = "-fx-background-color: white; -fx-border-color: #333;";
    private final String USERS_FILE = "users.txt";

    private TextArea sysLogsTextArea;
    private TableView<Session> activeSessionsTable;
    private ObservableList<Session> sessionsData;
    private TextField broadcastMsgField;
    private Label uptimeLabel;
    private ListView<String> usersListView;
    private ObservableList<String> registeredUsersData;

    private TableView<Stat> mailboxTable;
    private ObservableList<Stat> mailboxData;
    private ComboBox<String> cmbFilter;

    private Label statusText;
    private Circle statusIndicator;
    private Label portLabel;

    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private final int PORT = 5000;
    private final int UDP_PORT = 5001;
    private long startTime;
    private Timer uptimeTimer;
    private int maxMessageSizeBytes = 65536;

    private ConcurrentHashMap<String, String> registeredUsers = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, ClientHandler> activeClients = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Set<String>> chatRooms = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Stat> userStatsMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, String> userStates = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, List<String>> offlinePMs = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, List<String>> roomHistory = new ConcurrentHashMap<>();
    private List<String> allSystemLogs = new ArrayList<>();

    // قائمة الغرف القابلة للملاحظة لتحديث واجهة الإدارة تلقائياً
    private ObservableList<RoomInfo> roomInfoList = FXCollections.observableArrayList();

    @Override
    public void start(Stage primaryStage) {
        loadUsersFromFile();
        sessionsData = FXCollections.observableArrayList();
        mailboxData = FXCollections.observableArrayList();

        // إعداد الغرف الافتراضية
        chatRooms.put("General", ConcurrentHashMap.newKeySet());
        chatRooms.put("Networks", ConcurrentHashMap.newKeySet());
        chatRooms.put("Java", ConcurrentHashMap.newKeySet());

        // تعبئة القائمة القابلة للملاحظة للغرف
        refreshRoomInfoList();

        for (String user : registeredUsers.keySet()) {
            Stat stat = new Stat(user, 0, 0, "0 (0.0KB)");
            userStatsMap.put(user, stat);
            mailboxData.add(stat);
        }

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #f0f0f0; -fx-font-family: 'Segoe UI', sans-serif; -fx-font-size: 12px;");

        statusText = new Label(" OFFLINE ");
        statusIndicator = new Circle(4, Color.RED);
        portLabel = new Label(" | PORT (TCP): Not Bound | PORT (UDP): Not Bound | ");

        HBox topBar = new HBox(5);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5, 10, 5, 10));
        topBar.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-background-color: #fafafa;");
        uptimeLabel = new Label("Uptime: 00:00:00");
        topBar.getChildren().addAll(new Label("SERVER STATUS: "), statusIndicator, statusText, portLabel, uptimeLabel);
        root.setTop(topBar);
        BorderPane.setMargin(topBar, new Insets(0, 0, 10, 0));

        HBox mainContent = new HBox(10);

        VBox leftColumn = new VBox(10);
        leftColumn.setPrefWidth(260);
        leftColumn.setMinWidth(260);

        Button btnManageRooms = new Button("Manage Rooms");
        btnManageRooms.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-background-color: #fafafa; -fx-font-weight: bold; -fx-padding: 8;");
        btnManageRooms.setMaxWidth(Double.MAX_VALUE);
        btnManageRooms.setOnAction(e -> openRoomManagerDialog());

        leftColumn.getChildren().addAll(
            createSectionBox("USER MANAGEMENT", createUserManagementContent()),
            btnManageRooms
        );

        VBox middleColumn = new VBox(10);
        HBox.setHgrow(middleColumn, Priority.ALWAYS);
        VBox activeSessionsBox = createSectionBox("ACTIVE SESSIONS & ROSTER", createActiveSessionsContent());
        VBox.setVgrow(activeSessionsBox, Priority.ALWAYS);
        middleColumn.getChildren().addAll(activeSessionsBox);

        VBox rightColumn = new VBox(10);
        rightColumn.setPrefWidth(300);
        VBox mailboxStatsBox = createSectionBox("MAILBOX STATISTICS (Real-Time)", createMailboxStatsContent());
        VBox.setVgrow(mailboxStatsBox, Priority.ALWAYS);
        VBox rightSystemLogsBox = createSectionBox("SERVER SETTINGS", createServerSettingsContent());
        rightColumn.getChildren().addAll(mailboxStatsBox, rightSystemLogsBox);

        mainContent.getChildren().addAll(leftColumn, middleColumn, rightColumn);
        root.setCenter(mainContent);

        VBox bottomLogs = createSectionBox("SYSTEM LOGS", createBottomLogsContent());
        root.setBottom(bottomLogs);
        BorderPane.setMargin(bottomLogs, new Insets(10, 0, 0, 0));

        Scene scene = new Scene(root, 1100, 720);
        primaryStage.setTitle("ChatLite Server Console");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> stopServer());
        primaryStage.show();

        startServer();
    }

    // تحديث قائمة roomInfoList بناءً على chatRooms الحالية
    private void refreshRoomInfoList() {
        Platform.runLater(() -> {
            roomInfoList.clear();
            for (Map.Entry<String, Set<String>> entry : chatRooms.entrySet()) {
                roomInfoList.add(new RoomInfo(entry.getKey(), entry.getValue().size()));
            }
        });
    }

    // تحديث غرفة معينة في roomInfoList (زيادة أو نقصان العدد)
    private void updateRoomCount(String roomName, int delta) {
        Platform.runLater(() -> {
            for (RoomInfo ri : roomInfoList) {
                if (ri.getName().equals(roomName)) {
                    ri.setCount(ri.getCount() + delta);
                    return;
                }
            }
            // إذا لم توجد الغرفة في القائمة (مثلاً غرفة جديدة لم تُضف بعد)
            if (delta > 0 && chatRooms.containsKey(roomName)) {
                roomInfoList.add(new RoomInfo(roomName, chatRooms.get(roomName).size()));
            }
        });
    }

    private void openRoomManagerDialog() {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Manage Chat Rooms");

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: #f9f9f9;");

        TableView<RoomInfo> roomTable = new TableView<>();
        // استخدام roomInfoList مباشرة
        roomTable.setItems(roomInfoList);

        TableColumn<RoomInfo, String> colName = new TableColumn<>("Room Name");
        colName.setCellValueFactory(c -> c.getValue().nameProperty());
        colName.setPrefWidth(150);

        TableColumn<RoomInfo, Number> colUsers = new TableColumn<>("Users Count");
        colUsers.setCellValueFactory(c -> c.getValue().countProperty());
        colUsers.setPrefWidth(100);

        roomTable.getColumns().addAll(colName, colUsers);

        HBox addBox = new HBox(10);
        TextField txtNewRoom = new TextField();
        txtNewRoom.setPromptText("Room name...");
        Button btnAdd = new Button("Add Room");
        btnAdd.setOnAction(e -> {
            String r = txtNewRoom.getText().trim();
            if (!r.isEmpty() && !chatRooms.containsKey(r)) {
                chatRooms.put(r, ConcurrentHashMap.newKeySet());
                // إضافة الغرفة إلى القائمة القابلة للملاحظة
                Platform.runLater(() -> roomInfoList.add(new RoomInfo(r, 0)));
                broadcastToAll("ROOM_ADDED " + r);
                logEvent("SYS", "Room created: " + r);
                txtNewRoom.clear();
            }
        });
        addBox.getChildren().addAll(txtNewRoom, btnAdd);

        Button btnDelete = new Button("Delete Selected Room");
        btnDelete.setMaxWidth(Double.MAX_VALUE);
        btnDelete.setStyle("-fx-base: #e74c3c; -fx-text-fill: white;");
        btnDelete.setOnAction(e -> {
            RoomInfo sel = roomTable.getSelectionModel().getSelectedItem();
            if (sel != null) {
                chatRooms.remove(sel.getName());
                // إزالة الغرفة من القائمة القابلة للملاحظة
                Platform.runLater(() -> roomInfoList.remove(sel));
                broadcastToAll("ROOM_DELETED " + sel.getName());
                logEvent("SYS", "Room deleted: " + sel.getName());
            }
        });

        root.getChildren().addAll(new Label("Current Rooms:"), roomTable, addBox, btnDelete);
        Scene scene = new Scene(root, 300, 400);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    public static class RoomInfo {
        private final SimpleStringProperty name;
        private final SimpleIntegerProperty count;

        public RoomInfo(String n, int c) {
            this.name = new SimpleStringProperty(n);
            this.count = new SimpleIntegerProperty(c);
        }

        public String getName() { return name.get(); }
        public SimpleStringProperty nameProperty() { return name; }
        public int getCount() { return count.get(); }
        public void setCount(int c) { count.set(c); }
        public SimpleIntegerProperty countProperty() { return count; }
    }

    private void loadUsersFromFile() {
        File file = new File(USERS_FILE);
        if (!file.exists()) {
            registeredUsers.put("Ahmed", "123");
            registeredUsers.put("Sara", "123");
            saveUsersToFile();
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) registeredUsers.put(parts[0], parts[1]);
            }
        } catch (IOException e) {}
    }

    private void saveUsersToFile() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(USERS_FILE))) {
            for (Map.Entry<String, String> entry : registeredUsers.entrySet()) pw.println(entry.getKey() + "," + entry.getValue());
        } catch (IOException e) {
            logEvent("ERROR", "Failed to save users.");
        }
    }

    private void startServer() {
        startTime = System.currentTimeMillis();
        uptimeTimer = new Timer(true);
        uptimeTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                long hours = elapsed / 3600;
                long mins = (elapsed % 3600) / 60;
                long secs = elapsed % 60;
                Platform.runLater(() -> uptimeLabel.setText(String.format("Uptime: %02d:%02d:%02d", hours, mins, secs)));
            }
        }, 1000, 1000);

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                isRunning = true;

                Platform.runLater(() -> {
                    statusText.setText(" ONLINE ");
                    statusIndicator.setFill(Color.LIMEGREEN);
                    portLabel.setText(" | PORT (TCP): " + PORT + " | PORT (UDP): " + UDP_PORT + " | ");
                });

                logEvent("INFO", "Server started on TCP:" + PORT + " | UDP:" + UDP_PORT);
                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(new ClientHandler(clientSocket)).start();
                }
            } catch (IOException e) {
                logEvent("ERROR", "Server Exception: " + e.getMessage());
                stopServer();
            }
        }).start();
    }

    private void stopServer() {
        isRunning = false;
        if (uptimeTimer != null) uptimeTimer.cancel();

        Platform.runLater(() -> {
            statusText.setText(" OFFLINE ");
            statusIndicator.setFill(Color.RED);
            portLabel.setText(" | PORT (TCP): Not Bound | PORT (UDP): Not Bound | ");
        });

        try {
            if (serverSocket != null) serverSocket.close();
            for(ClientHandler handler : activeClients.values()) handler.disconnect();
        } catch (IOException e) {}
    }

    private void logEvent(String type, String message) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String logEntry = "[" + time + "] " + type + ": " + message + "\n";
        Platform.runLater(() -> {
            allSystemLogs.add(logEntry);
            if (cmbFilter != null) {
                String currentFilter = cmbFilter.getValue();
                if (currentFilter == null || currentFilter.equals("All") || logEntry.contains("] " + currentFilter + ":")) {
                    sysLogsTextArea.appendText(logEntry);
                }
            } else {
                sysLogsTextArea.appendText(logEntry);
            }
        });
    }

    private void broadcastToAll(String msg) {
        for (ClientHandler ch : activeClients.values()) ch.sendMessage(msg);
    }

    private class ClientHandler implements Runnable {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String username;
        private String ipAddress;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.ipAddress = socket.getInetAddress().getHostAddress();
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.getBytes().length > maxMessageSizeBytes) {
                        out.println("SERVER_MSG Message size exceeds maximum limit.");
                        continue;
                    }
                    handleCommand(line);
                }
            } catch (IOException e) {
                logEvent("ERROR", (username != null ? username : "Client") + " disconnected.");
            } finally {
                disconnect();
            }
        }

        private void handleCommand(String commandLine) {
            String[] parts = commandLine.split(" ", 3);
            String command = parts[0].toUpperCase();

            switch (command) {
                case "HELLO":
                    if (parts.length > 2) {
                        String reqUser = parts[1];
                        String reqPass = parts[2];
                        if (registeredUsers.containsKey(reqUser) && registeredUsers.get(reqUser).equals(reqPass)) {
                            if (activeClients.containsKey(reqUser)) {
                                out.println("ERROR: User already logged in");
                            } else {
                                username = reqUser;
                                activeClients.put(username, this);
                                userStates.put(username, "ACTIVE");
                                out.println("200 WELCOME");
                                logEvent("AUTH", "'" + username + "' logged in from " + ipAddress);
                                Platform.runLater(() -> sessionsData.add(new Session(username, "ACTIVE", ipAddress)));
                                broadcastToAll("STATE_UPDATE " + username + " ACTIVE");

                                if (offlinePMs.containsKey(username)) {
                                    for(String m : offlinePMs.get(username)) out.println(m);
                                    offlinePMs.remove(username);
                                }
                            }
                        } else {
                            out.println("ERROR: Invalid username or password.");
                            logEvent("AUTH", "Failed login: " + reqUser);
                        }
                    }
                    break;
                case "STATE":
                    if (parts.length > 1 && username != null) {
                        String newState = parts[1].toUpperCase();
                        userStates.put(username, newState);
                        broadcastToAll("STATE_UPDATE " + username + " " + newState);
                        Platform.runLater(() -> {
                            sessionsData.stream().filter(s -> s.getUser().equals(username)).findFirst().ifPresent(s -> s.setStatus(newState));
                            activeSessionsTable.refresh();
                        });
                    }
                    break;
                case "JOIN":
                    if (parts.length > 1 && username != null) {
                        String room = parts[1];
                        if (chatRooms.containsKey(room)) {
                            if (chatRooms.get(room).add(username)) {
                                out.println("210 JOINED " + room);
                                logEvent("INFO", "User '" + username + "' joined room: " + room);
                                // تحديث واجهة إدارة الغرف: زيادة العدد بمقدار 1
                                updateRoomCount(room, 1);
                                if (roomHistory.containsKey(room)) {
                                    for (String m : roomHistory.get(room)) out.println(m);
                                }
                            }
                        } else {
                            // إذا كانت الغرفة غير موجودة، يتم إنشاؤها تلقائياً (اختياري)
                            chatRooms.put(room, ConcurrentHashMap.newKeySet());
                            chatRooms.get(room).add(username);
                            out.println("210 JOINED " + room);
                            logEvent("INFO", "Room created and user '" + username + "' joined: " + room);
                            // إضافة الغرفة إلى القائمة القابلة للملاحظة
                            Platform.runLater(() -> roomInfoList.add(new RoomInfo(room, 1)));
                        }
                    }
                    break;
                case "LEAVE":
                    if (parts.length > 1 && username != null) {
                        String room = parts[1];
                        if(chatRooms.containsKey(room)) {
                            if (chatRooms.get(room).remove(username)) {
                                out.println("215 LEFT " + room);
                                logEvent("INFO", "User '" + username + "' left room: " + room);
                                // تحديث واجهة إدارة الغرف: نقصان العدد بمقدار 1
                                updateRoomCount(room, -1);
                            }
                        }
                    }
                    break;
                case "MSG":
                    if (parts.length > 2 && username != null) {
                        String room = parts[1];
                        String msg = parts[2];
                        String fullMsg = "MSG " + room + " " + username + ": " + msg;
                        int msgBytes = fullMsg.getBytes().length;
                        roomHistory.computeIfAbsent(room, k -> new CopyOnWriteArrayList<>()).add(fullMsg);
                        Set<String> usersInRoom = chatRooms.get(room);
                        if (usersInRoom != null) {
                            for (String u : usersInRoom) {
                                ClientHandler ch = activeClients.get(u);
                                if (ch != null && !u.equals(username)) {
                                    ch.sendMessage(fullMsg);
                                    updateMailboxStats(u, true, msgBytes);
                                }
                            }
                            out.println("211 SENT");
                            updateMailboxStats(username, false, msgBytes);
                        }
                    }
                    break;
                case "PM":
                    if (parts.length > 2 && username != null) {
                        String target = parts[1];
                        String msg = parts[2];
                        String fullMsg = "PM " + username + ": " + msg;
                        int msgBytes = fullMsg.getBytes().length;
                        ClientHandler ch = activeClients.get(target);
                        if (ch != null) {
                            ch.sendMessage(fullMsg);
                            out.println("212 PRIVATE SENT");
                            updateMailboxStats(username, false, msgBytes);
                            updateMailboxStats(target, true, msgBytes);
                        } else if (registeredUsers.containsKey(target)) {
                            offlinePMs.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add("PM " + username + " (Offline): " + msg);
                            out.println("212 PRIVATE SAVED OFFLINE");
                            updateMailboxStats(username, false, msgBytes);
                            updateMailboxStats(target, true, msgBytes);
                        } else {
                            out.println("ERROR: User not found");
                        }
                    }
                    break;
                case "USERS":
                    out.println("213 " + activeClients.size());
                    for(String u : activeClients.keySet()) {
                        out.println("213U " + u + " " + userStates.getOrDefault(u, "ACTIVE"));
                    }
                    out.println("213 END");
                    break;
                case "ROOMS":
                    for(String r : chatRooms.keySet()) out.println("214 " + r);
                    out.println("214 END");
                    break;
                case "QUIT":
                    out.println("221 BYE");
                    disconnect();
                    break;
            }
        }

        public void sendMessage(String msg) {
            if (out != null) out.println(msg);
        }

        public void disconnect() {
            try {
                if (username != null) {
                    activeClients.remove(username);
                    userStates.remove(username);
                    broadcastToAll("STATE_UPDATE " + username + " OFFLINE");
                    // إزالة المستخدم من جميع الغرف وتحديث العدد
                    for (Map.Entry<String, Set<String>> entry : chatRooms.entrySet()) {
                        if (entry.getValue().remove(username)) {
                            updateRoomCount(entry.getKey(), -1);
                        }
                    }
                    Platform.runLater(() -> sessionsData.removeIf(session -> session.getUser().equals(username)));
                    logEvent("INFO", "User '" + username + "' disconnected.");
                }
                if (socket != null) socket.close();
            } catch (IOException e) {}
        }
    }

    private void updateMailboxStats(String username, boolean isReceiving, int messageSizeBytes) {
        Platform.runLater(() -> {
            Stat stat = userStatsMap.get(username);
            if(stat != null) {
                if(isReceiving) stat.setInbox(stat.getInbox() + 1);
                else stat.setSent(stat.getSent() + 1);
                stat.addMessageSize(messageSizeBytes);
            }
        });
    }

    private VBox createSectionBox(String title, javafx.scene.Node content) {
        VBox box = new VBox();
        box.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-background-color: #fafafa;");
        Label header = new Label(title);
        header.setMaxWidth(Double.MAX_VALUE);
        header.setStyle("-fx-border-color: transparent transparent #333 transparent; -fx-border-width: 1; -fx-padding: 5; -fx-background-color: #eaeaea; -fx-font-weight: bold;");
        VBox contentArea = new VBox(content);
        contentArea.setPadding(new Insets(8));
        contentArea.setSpacing(8);
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        box.getChildren().addAll(header, contentArea);
        return box;
    }

    private void styleButton(Button btn) {
        btn.setStyle("-fx-background-color: white; -fx-border-color: #333; -fx-border-radius: 2; -fx-padding: 3 10; -fx-cursor: hand;");
    }

    private VBox createUserManagementContent() {
        VBox content = new VBox(10);
        VBox addUserBox = new VBox(8);
        addUserBox.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-padding: 8;");
        GridPane grid = new GridPane();
        grid.setVgap(8); grid.setHgap(8);
        Label lblUsername = new Label("Username:"); lblUsername.setMinWidth(Region.USE_PREF_SIZE);
        TextField userField = new TextField(); userField.setStyle(CONTROL_STYLE);
        GridPane.setHgrow(userField, Priority.ALWAYS);
        Label lblPassword = new Label("Password:"); lblPassword.setMinWidth(Region.USE_PREF_SIZE);
        PasswordField passField = new PasswordField(); passField.setStyle(CONTROL_STYLE);
        GridPane.setHgrow(passField, Priority.ALWAYS);
        grid.add(lblUsername, 0, 0); grid.add(userField, 1, 0);
        grid.add(lblPassword, 0, 1); grid.add(passField, 1, 1);
        Button btnCreate = new Button("Create User");
        styleButton(btnCreate); btnCreate.setMaxWidth(Double.MAX_VALUE);

        btnCreate.setOnAction(e -> {
            String uname = userField.getText().trim();
            String pass = passField.getText();
            if(!uname.isEmpty() && !pass.isEmpty() && !registeredUsers.containsKey(uname)) {
                registeredUsers.put(uname, pass);
                saveUsersToFile();
                registeredUsersData.add(uname);
                Stat stat = new Stat(uname, 0, 0, "0 (0.0KB)");
                userStatsMap.put(uname, stat);
                mailboxData.add(stat);
                logEvent("SYS", "New user created: " + uname);
                userField.clear(); passField.clear();
            }
        });
        addUserBox.getChildren().addAll(new Label("Add New User"), grid, btnCreate);

        VBox existUserBox = new VBox(8);
        existUserBox.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-padding: 8;");
        VBox.setVgrow(existUserBox, Priority.ALWAYS);
        registeredUsersData = FXCollections.observableArrayList(registeredUsers.keySet());
        usersListView = new ListView<>(registeredUsersData);
        usersListView.setStyle(CONTROL_STYLE);
        VBox.setVgrow(usersListView, Priority.ALWAYS);

        Button btnDelete = new Button("Delete Selected");
        styleButton(btnDelete); btnDelete.setMaxWidth(Double.MAX_VALUE);
        btnDelete.setOnAction(e -> {
            String selected = usersListView.getSelectionModel().getSelectedItem();
            if(selected != null) {
                registeredUsers.remove(selected);
                saveUsersToFile();
                registeredUsersData.remove(selected);
                mailboxData.remove(userStatsMap.remove(selected));
                logEvent("SYS", "User deleted: " + selected);
                if (activeClients.containsKey(selected)) {
                    activeClients.get(selected).sendMessage("SERVER_MSG Account deleted.");
                    activeClients.get(selected).disconnect();
                }
            }
        });

        existUserBox.getChildren().addAll(new Label("Existing Users:"), usersListView, btnDelete);
        content.getChildren().addAll(addUserBox, existUserBox);
        return content;
    }

    private VBox createActiveSessionsContent() {
        VBox box = new VBox(8);
        activeSessionsTable = new TableView<>();
        activeSessionsTable.setStyle("-fx-border-color: #333; -fx-background-color: white;");
        VBox.setVgrow(activeSessionsTable, Priority.ALWAYS);
        TableColumn<Session, String> colUser = new TableColumn<>("Username");
        colUser.setCellValueFactory(cell -> cell.getValue().userProperty());
        TableColumn<Session, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(cell -> cell.getValue().statusProperty());
        TableColumn<Session, String> colIP = new TableColumn<>("IP Address");
        colIP.setCellValueFactory(cell -> cell.getValue().ipProperty());
        activeSessionsTable.getColumns().addAll(colUser, colStatus, colIP);
        activeSessionsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        activeSessionsTable.setItems(sessionsData);

        HBox botControls1 = new HBox(10);
        Button btnKick = new Button("Kick User");
        styleButton(btnKick);
        btnKick.setOnAction(e -> {
            Session selected = activeSessionsTable.getSelectionModel().getSelectedItem();
            if (selected != null && activeClients.containsKey(selected.getUser())) {
                activeClients.get(selected.getUser()).sendMessage("SERVER_MSG You have been kicked.");
                activeClients.get(selected.getUser()).disconnect();
            }
        });

        broadcastMsgField = new TextField();
        broadcastMsgField.setPromptText("Type Broadcast Msg and press Enter...");
        broadcastMsgField.setStyle(CONTROL_STYLE);
        HBox.setHgrow(broadcastMsgField, Priority.ALWAYS);
        broadcastMsgField.setOnAction(e -> {
            String msg = broadcastMsgField.getText();
            if(msg != null && !msg.trim().isEmpty()) {
                broadcastToAll("SERVER_BROADCAST: " + msg);
                logEvent("BROADCAST", msg);
                broadcastMsgField.clear();
            }
        });

        botControls1.getChildren().addAll(btnKick, broadcastMsgField);
        box.getChildren().addAll(activeSessionsTable, botControls1);
        return box;
    }

    private VBox createMailboxStatsContent() {
        VBox box = new VBox(8);
        TableView<Stat> table1 = new TableView<>();
        table1.setStyle("-fx-border-color: #333; -fx-background-color: white;");
        TableColumn<Stat, String> colUser = new TableColumn<>("User"); colUser.setCellValueFactory(c -> c.getValue().userProperty());
        TableColumn<Stat, Number> colInbox = new TableColumn<>("Inbox"); colInbox.setCellValueFactory(c -> c.getValue().inboxProperty());
        TableColumn<Stat, Number> colSent = new TableColumn<>("Sent"); colSent.setCellValueFactory(c -> c.getValue().sentProperty());
        TableColumn<Stat, String> colArch = new TableColumn<>("Archv Size"); colArch.setCellValueFactory(c -> c.getValue().archvProperty());
        table1.getColumns().addAll(colUser, colInbox, colSent, colArch);
        table1.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table1.setItems(mailboxData);
        VBox.setVgrow(table1, Priority.ALWAYS);

        Button btnCleanup = new Button("Force Cleanup Now");
        styleButton(btnCleanup);
        btnCleanup.setMaxWidth(Double.MAX_VALUE);

        btnCleanup.setOnAction(e -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            String ts = sdf.format(new Date());
            String fileName = "ArchiveStats_" + ts + ".txt";

            try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
                writer.println("=== CHATLITE SERVER ARCHIVE ===");
                writer.println("Timestamp  : " + ts);
                writer.println("TCP Port   : " + PORT);
                writer.println("UDP Port   : " + UDP_PORT + "\n");

                writer.println("--- MAILBOX STATISTICS ---");
                writer.printf("%-15s %-8s %-8s %-15s\n", "User", "Inbox", "Sent", "Total Bytes");
                for (Stat s : mailboxData) {
                    writer.printf("%-15s %-8d %-8d %-15d\n", s.getUser(), s.getInbox(), s.getSent(), s.totalBytes);
                }

                writer.println("\n--- REGISTERED USERS ---");
                for(String u : registeredUsers.keySet()) writer.println("  " + u);

                writer.println("\n--- ACTIVE CHAT ROOMS ---");
                for(Map.Entry<String, Set<String>> entry : chatRooms.entrySet()) {
                    writer.println("  " + entry.getKey() + " -> " + entry.getValue().toString());
                }

                writer.println("\n--- SYSTEM LOG HISTORY ---");
                for(String log : allSystemLogs) writer.print(log);

                logEvent("SYS", "Mailbox stats archived to '" + fileName + "'");
            } catch (IOException ex) {
                logEvent("ERROR", "Failed to archive stats.");
            }

            for(Stat s : mailboxData) {
                s.setInbox(0);
                s.setSent(0);
                s.totalBytes = 0;
                s.archv.set("0 (0.0KB)");
            }
            logEvent("SYS", "Mailbox cleanup executed.");
        });

        box.getChildren().addAll(table1, btnCleanup);
        return box;
    }

    private VBox createServerSettingsContent() {
        VBox settingsContent = new VBox(10);
        HBox row1 = new HBox(10); row1.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> combo1 = new ComboBox<>(FXCollections.observableArrayList("64 KB", "128 KB", "256 KB"));
        combo1.setStyle(CONTROL_STYLE); combo1.getSelectionModel().selectFirst();
        row1.getChildren().addAll(new Label("Max Msg Size:"), combo1);

        HBox row2 = new HBox(10); row2.setAlignment(Pos.CENTER_LEFT);
        ComboBox<String> comboIdle = new ComboBox<>(FXCollections.observableArrayList("Never", "5 mins", "15 mins"));
        comboIdle.setStyle(CONTROL_STYLE); comboIdle.getSelectionModel().selectFirst();
        row2.getChildren().addAll(new Label("Auto-Kick Idle:"), comboIdle);

        Button btnApply = new Button("Apply Settings");
        styleButton(btnApply);
        btnApply.setOnAction(e -> {
            String val = combo1.getValue();
            if (val.contains("256")) maxMessageSizeBytes = 262144;
            else if (val.contains("128")) maxMessageSizeBytes = 131072;
            else maxMessageSizeBytes = 65536;
            logEvent("SYS", "Settings Applied - Max Msg: " + val + " | Idle Kick: " + comboIdle.getValue());
        });
        settingsContent.getChildren().addAll(row1, row2, btnApply);
        return settingsContent;
    }

    private VBox createBottomLogsContent() {
        VBox box = new VBox(5);
        HBox controls = new HBox(8); controls.setAlignment(Pos.CENTER_RIGHT);
        Button btnClear = new Button("Clear Logs"); styleButton(btnClear);
        btnClear.setOnAction(e -> sysLogsTextArea.clear());
        Button btnSave = new Button("Save Logs to .txt"); styleButton(btnSave);
        btnSave.setOnAction(e -> {
            try (PrintWriter writer = new PrintWriter(new FileWriter("ServerLogs.txt", true))) {
                writer.println("--- LOG EXPORT: " + new Date().toString() + " ---");
                writer.println(sysLogsTextArea.getText());
                logEvent("SYS", "Logs saved to 'ServerLogs.txt'");
            } catch (IOException ex) {}
        });
        cmbFilter = new ComboBox<>(FXCollections.observableArrayList("All", "INFO", "ERROR", "AUTH", "SYS", "BROADCAST"));
        cmbFilter.setStyle(CONTROL_STYLE); cmbFilter.getSelectionModel().selectFirst();
        cmbFilter.setOnAction(e -> {
            String selectedFilter = cmbFilter.getValue();
            sysLogsTextArea.clear();
            for (String log : allSystemLogs) {
                if (selectedFilter.equals("All") || log.contains("] " + selectedFilter + ":")) sysLogsTextArea.appendText(log);
            }
        });
        controls.getChildren().addAll(btnClear, btnSave, cmbFilter);
        sysLogsTextArea = new TextArea();
        sysLogsTextArea.setStyle(CONTROL_STYLE + " -fx-font-family: 'Consolas', monospace;");
        sysLogsTextArea.setEditable(false);
        sysLogsTextArea.setPrefRowCount(6);
        box.getChildren().addAll(controls, sysLogsTextArea);
        return box;
    }

    public static class Session {
        private final SimpleStringProperty user, status, ip;
        public Session(String u, String s, String i) {
            user = new SimpleStringProperty(u); status = new SimpleStringProperty(s); ip = new SimpleStringProperty(i);
        }
        public SimpleStringProperty userProperty() { return user; }
        public String getUser() { return user.get(); }
        public SimpleStringProperty statusProperty() { return status; }
        public void setStatus(String s) { status.set(s); }
        public SimpleStringProperty ipProperty() { return ip; }
    }

    public static class Stat {
        private final SimpleStringProperty user, archv;
        private final SimpleIntegerProperty inbox, sent;
        public int totalBytes = 0;
        public Stat(String u, int i, int s, String a) {
            user = new SimpleStringProperty(u); inbox = new SimpleIntegerProperty(i);
            sent = new SimpleIntegerProperty(s); archv = new SimpleStringProperty(a);
        }
        public SimpleStringProperty userProperty() { return user; }
        public SimpleIntegerProperty inboxProperty() { return inbox; }
        public SimpleIntegerProperty sentProperty() { return sent; }
        public SimpleStringProperty archvProperty() { return archv; }
        public String getUser() { return user.get(); }
        public int getInbox() { return inbox.get(); }
        public void setInbox(int val) { this.inbox.set(val); }
        public int getSent() { return sent.get(); }
        public void setSent(int val) { this.sent.set(val); }
        public void addMessageSize(int bytes) {
            totalBytes += bytes;
            int totalMsgs = inbox.get() + sent.get();
            double kb = totalBytes / 1024.0;
            archv.set(totalMsgs + " (" + String.format("%.1f", kb) + "KB)");
        }
    }

    public static void main(String[] args) { launch(args); }
}