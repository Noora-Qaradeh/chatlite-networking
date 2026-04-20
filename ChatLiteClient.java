import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class ChatLiteClient extends Application {

    private Label logLabel;
    private Label uptimeLabel;
    private TableView<Message> chatTable;
    private ObservableList<Message> chatData;
    private FilteredList<Message> filteredData;
    
    private ListView<HBox> onlineUsersListView;
    private ObservableList<HBox> onlineUsersData;
    private ObservableList<String> userNamesList; 
    
    private ComboBox<String> pmCombo1;
    private TextArea pmArea1;
    private ComboBox<String> pmCombo2;
    private TextArea pmArea2;
    private TextField mainChatInput;
    
    private ComboBox<String> chatRoomsCombo;
    private ComboBox<String> targetRoomCombo;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private String currentUsername;
    private String SERVER_IP = "127.0.0.1";
    private boolean isConnected = false;
    private long startTime;

    // المجموعة المحفوظة للغرف التي انضم إليها المستخدم
    private Set<String> myRooms = new HashSet<>();

    @Override
    public void start(Stage primaryStage) {
        Dialog<String[]> loginDialog = new Dialog<>();
        loginDialog.setTitle("ChatLite Login");
        loginDialog.setHeaderText("Enter Server Details");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        TextField ipField = new TextField("127.0.0.1");
        TextField userField = new TextField();
        PasswordField passField = new PasswordField();
        
        grid.add(new Label("Server IP:"), 0, 0);
        grid.add(ipField, 1, 0);
        grid.add(new Label("Username:"), 0, 1);
        grid.add(userField, 1, 1);
        grid.add(new Label("Password:"), 0, 2);
        grid.add(passField, 1, 2);
        
        loginDialog.getDialogPane().setContent(grid);
        loginDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        loginDialog.setResultConverter(dialogButton -> {
            if (dialogButton == ButtonType.OK) return new String[]{ipField.getText(), userField.getText(), passField.getText()};
            return null;
        });
        
        String[] result = loginDialog.showAndWait().orElse(null);
        if (result != null && !result[1].trim().isEmpty() && !result[2].trim().isEmpty()) {
            SERVER_IP = result[0].trim();
            currentUsername = result[1].trim();
            connectToServer(result[2].trim());
            buildUI(primaryStage);
        } else {
            System.exit(0);
        }
    }

    private void buildUI(Stage primaryStage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.setStyle("-fx-background-color: #f0f0f0; -fx-font-family: 'Comic Sans MS', sans-serif;");

        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5, 10, 5, 10));
        topBar.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-background-color: #fafafa;");
        Label connLabel = new Label("CONNECTED TO: " + SERVER_IP + ":5000");
        uptimeLabel = new Label("Uptime: 00:00:00");
        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);
        topBar.getChildren().addAll(connLabel, spacer1, uptimeLabel);
        root.setTop(topBar);
        BorderPane.setMargin(topBar, new Insets(0, 0, 10, 0));

        HBox mainContent = new HBox(10);
        
        VBox leftColumn = new VBox(10);
        leftColumn.setPrefWidth(200);
        leftColumn.getChildren().addAll(
            createSectionBox("CHAT ROOMS", createChatRoomsList()),
            createSectionBox("ONLINE USERS", createOnlineUsersList()),
            createSectionBox("USER STATUS", createUserStatusList())
        );

        VBox middleColumn = new VBox(10);
        HBox.setHgrow(middleColumn, Priority.ALWAYS);
        VBox chatMessagesBox = createSectionBox("CHAT MESSAGES", createChatTable());
        VBox.setVgrow(chatMessagesBox, Priority.ALWAYS);
        
        HBox inlineInput = new HBox(5);
        mainChatInput = new TextField();
        mainChatInput.setPromptText("Type message here...");
        mainChatInput.setStyle("-fx-background-color: white; -fx-border-color: #333;");
        HBox.setHgrow(mainChatInput, Priority.ALWAYS);
        Button sendBtn1 = new Button("SEND");
        styleButton(sendBtn1);
        sendBtn1.setOnAction(e -> sendRoomMessage());
        mainChatInput.setOnAction(e -> sendRoomMessage());
        inlineInput.getChildren().addAll(mainChatInput, sendBtn1);
        chatMessagesBox.getChildren().add(inlineInput);
        
        VBox msgInputBox = createSectionBox("MESSAGE INPUT", createMessageInput());
        
        TextField searchField = new TextField();
        searchField.setPromptText("SEARCH: Find by user/message... 🔍");
        searchField.setStyle("-fx-background-color: white; -fx-border-color: #333;");
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            filteredData.setPredicate(message -> {
                if (newValue == null || newValue.isEmpty()) return true;
                String lowerCaseFilter = newValue.toLowerCase();
                if (message.getUser().toLowerCase().contains(lowerCaseFilter)) return true;
                if (message.getMessage().toLowerCase().contains(lowerCaseFilter)) return true;
                return false;
            });
        });

        middleColumn.getChildren().addAll(chatMessagesBox, msgInputBox, searchField);

        VBox rightColumn = new VBox(10);
        rightColumn.setPrefWidth(220);
        rightColumn.getChildren().addAll(
            createSectionBox("PRIVATE MSG", createPrivateMsgBox1())
        );

        mainContent.getChildren().addAll(leftColumn, middleColumn, rightColumn);
        root.setCenter(mainContent);

        HBox bottomBar = new HBox(10);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(5, 10, 5, 10));
        bottomBar.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-background-color: #fafafa;");
        logLabel = new Label("Initializing...");
        Region bottomSpacer = new Region();
        HBox.setHgrow(bottomSpacer, Priority.ALWAYS);
        HBox dots = new HBox(3);
        dots.getChildren().addAll(new Circle(3, Color.TRANSPARENT) {{ setStroke(Color.BLACK); }}, new Circle(3, Color.TRANSPARENT) {{ setStroke(Color.BLACK); }}, new Circle(3, Color.TRANSPARENT) {{ setStroke(Color.BLACK); }});
        bottomBar.getChildren().addAll(logLabel, bottomSpacer, dots);
        root.setBottom(bottomBar);
        BorderPane.setMargin(bottomBar, new Insets(10, 0, 0, 0));

        Scene scene = new Scene(root, 950, 600);
        primaryStage.setTitle("ChatLite Client - [" + currentUsername + "]");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(e -> {
            if (out != null) out.println("QUIT");
            System.exit(0);
        });
        primaryStage.show();
    }

    private void connectToServer(String password) {
        new Thread(() -> {
            try {
                socket = new Socket(SERVER_IP, 5000);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                
                out.println("HELLO " + currentUsername + " " + password);
                String response = in.readLine();
                
                if (response != null && response.startsWith("200")) {
                    isConnected = true;
                    updateLog("Connected successfully.");
                    startUptimeTimer();

                    // تحميل الغرف المحفوظة والانضمام إليها تلقائياً
                    myRooms = loadRoomsFromFile();
                    for (String room : myRooms) {
                        out.println("JOIN " + room);
                    }

                    out.println("USERS");
                    String line;
                    while ((line = in.readLine()) != null) {
                        handleIncomingMessage(line);
                    }
                } else {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR, "Login Failed! Wrong credentials.");
                        alert.showAndWait();
                        System.exit(0);
                    });
                }
            } catch (IOException e) {
                updateLog("Connection Error.");
            }
        }).start();
    }

    private void handleIncomingMessage(String line) {
        String time = new SimpleDateFormat("hh:mm a").format(new Date());

        if (line.startsWith("MSG ")) {
            try {
                String[] parts = line.split(" ", 3);
                String room = parts[1];
                String userAndMsg = parts[2];
                String user = userAndMsg.split(":")[0];
                String msg = userAndMsg.substring(user.length() + 1).trim();
                Platform.runLater(() -> chatData.add(new Message(user, "[" + room + "] " + msg, time)));
            } catch (Exception ignored) {}
        } 
        else if (line.startsWith("PM ")) {
            try {
                String userAndMsg = line.substring(3);
                String user = userAndMsg.split(":")[0];
                String msg = userAndMsg.substring(user.length() + 1).trim();
                Platform.runLater(() -> pmArea1.appendText(time + " [" + user + "]: " + msg + "\n"));
            } catch (Exception ignored) {}
        } 
        else if (line.startsWith("SERVER_BROADCAST:")) {
            String msg = line.substring(17);
            Platform.runLater(() -> chatData.add(new Message("SERVER", "📢 " + msg, time)));
        }
        else if (line.startsWith("STATE_UPDATE ")) {
            String[] parts = line.split(" ");
            if(parts.length >= 3) {
                Platform.runLater(() -> {
                    out.println("USERS");
                });
            }
        }
        else if (line.matches("^213 \\d+$")) {
            Platform.runLater(() -> {
                onlineUsersData.clear();
                userNamesList.clear();
            });
        }
        else if (line.startsWith("213U ")) {
            String[] parts = line.split(" ");
            if(parts.length >= 3) {
                String user = parts[1];
                String state = parts[2];
                Platform.runLater(() -> {
                    if(!user.equals(currentUsername)) {
                        userNamesList.add(user);
                        onlineUsersData.add(createStatusUserRow(getColorForState(state), user));
                    }
                });
            }
        }
        else if(line.startsWith("210 JOINED")) {
            String room = line.split(" ")[2];
            Platform.runLater(() -> {
                if(!targetRoomCombo.getItems().contains(room)) {
                    targetRoomCombo.getItems().add(room);
                    targetRoomCombo.getSelectionModel().select(room);
                }
            });
            if (!myRooms.contains(room)) {
                myRooms.add(room);
                saveRoomsToFile();
            }
        }
        else if(line.startsWith("215 LEFT")) {
            String room = line.split(" ")[2];
            Platform.runLater(() -> {
                targetRoomCombo.getItems().remove(room);
                chatData.removeIf(m -> m.getMessage().startsWith("[" + room + "] "));
            });
            // إزالة الغرفة من الملف إذا كان المستخدم قد غادرها
            if (myRooms.contains(room)) {
                myRooms.remove(room);
                saveRoomsToFile();
            }
        }
        else if(line.startsWith("SERVER_MSG")) {
            updateLog(line);
            if (line.contains("kicked") || line.contains("deleted") || line.contains("exceeds")) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.WARNING, line);
                    alert.showAndWait();
                    if(line.contains("kicked") || line.contains("deleted")) System.exit(0);
                });
            }
        }
    }

    private void saveRoomsToFile() {
        Path path = Paths.get("rooms_" + currentUsername + ".txt");
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            for (String room : myRooms) {
                writer.write(room);
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Set<String> loadRoomsFromFile() {
        Set<String> rooms = new HashSet<>();
        Path path = Paths.get("rooms_" + currentUsername + ".txt");
        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        rooms.add(line.trim());
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return rooms;
    }

    private Color getColorForState(String state) {
        switch(state.toUpperCase()) {
            case "ACTIVE": return Color.valueOf("#a8d5ba");
            case "BUSY": return Color.valueOf("#f9e79f");
            case "AWAY": return Color.LIGHTGRAY;
            default: return Color.WHITE;
        }
    }

    private void sendRoomMessage() {
        String room = targetRoomCombo.getValue();
        String text = mainChatInput.getText().trim();
        if (room != null && !text.isEmpty() && isConnected) {
            out.println("MSG " + room + " " + text);
            String time = new SimpleDateFormat("hh:mm a").format(new Date());
            chatData.add(new Message("Me", "[" + room + "] " + text, time));
            mainChatInput.clear();
        }
    }

    private void updateLog(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        Platform.runLater(() -> logLabel.setText("[" + time + "] " + msg));
    }

    private void startUptimeTimer() {
        startTime = System.currentTimeMillis();
        Thread timer = new Thread(() -> {
            try {
                while (true) {
                    long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                    long h = elapsed / 3600;
                    long m = (elapsed % 3600) / 60;
                    long s = elapsed % 60;
                    Platform.runLater(() -> uptimeLabel.setText(String.format("Uptime: %02d:%02d:%02d", h, m, s)));
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {}
        });
        timer.setDaemon(true);
        timer.start();
    }

    private VBox createSectionBox(String title, javafx.scene.Node content) {
        VBox box = new VBox();
        box.setStyle("-fx-border-color: #333; -fx-border-width: 1; -fx-background-color: #fafafa;");
        Label header = new Label(title);
        header.setMaxWidth(Double.MAX_VALUE);
        header.setStyle("-fx-border-color: transparent transparent #333 transparent; -fx-border-width: 1; -fx-padding: 5; -fx-background-color: #eaeaea;");
        VBox contentArea = new VBox(content);
        contentArea.setPadding(new Insets(5));
        contentArea.setSpacing(5);
        VBox.setVgrow(contentArea, Priority.ALWAYS);
        box.getChildren().addAll(header, contentArea);
        return box;
    }

    private void styleButton(Button btn) {
        btn.setStyle("-fx-background-color: white; -fx-border-color: #333; -fx-border-radius: 2; -fx-padding: 3 10; -fx-cursor: hand;");
    }

    private VBox createChatRoomsList() {
        VBox box = new VBox(5);
        chatRoomsCombo = new ComboBox<>(FXCollections.observableArrayList("General", "Networks", "Java"));
        chatRoomsCombo.getSelectionModel().selectFirst();
        chatRoomsCombo.setMaxWidth(Double.MAX_VALUE);
        chatRoomsCombo.setStyle("-fx-background-color: white; -fx-border-color: #333;");
        
        HBox btns = new HBox(5);
        Button btnJoin = new Button("Join");
        styleButton(btnJoin);
        Button btnLeave = new Button("Leave");
        styleButton(btnLeave);
        
        btnJoin.setOnAction(e -> {
            if(isConnected) out.println("JOIN " + chatRoomsCombo.getValue());
        });
        btnLeave.setOnAction(e -> {
            if(isConnected) out.println("LEAVE " + chatRoomsCombo.getValue());
        });
        
        btns.getChildren().addAll(btnJoin, btnLeave);
        box.getChildren().addAll(chatRoomsCombo, btns);
        return box;
    }

    private VBox createOnlineUsersList() {
        VBox box = new VBox(5);
        onlineUsersData = FXCollections.observableArrayList();
        userNamesList = FXCollections.observableArrayList();
        onlineUsersListView = new ListView<>(onlineUsersData);
        onlineUsersListView.setStyle("-fx-background-color: white; -fx-border-color: #333;");
        onlineUsersListView.setPrefHeight(120);
        
        Button refreshBtn = new Button("Refresh Users");
        styleButton(refreshBtn);
        VBox.setMargin(refreshBtn, new Insets(10, 0, 0, 0));
        refreshBtn.setOnAction(e -> {
            if(isConnected) out.println("USERS");
        });
        
        box.getChildren().addAll(onlineUsersListView, refreshBtn);
        return box;
    }

    private HBox createStatusUserRow(Color color, String name) {
        Circle dot = new Circle(5, color);
        dot.setStroke(Color.BLACK);
        HBox row = new HBox(5, dot, new Label(name));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private VBox createUserStatusList() {
        VBox box = new VBox(5);
        ToggleGroup group = new ToggleGroup();
        RadioButton rbActive = new RadioButton("Active");
        rbActive.setToggleGroup(group);
        rbActive.setSelected(true);
        RadioButton rbBusy = new RadioButton("Busy");
        rbBusy.setToggleGroup(group);
        RadioButton rbAway = new RadioButton("Away");
        rbAway.setToggleGroup(group);
        
        rbActive.setOnAction(e -> { if(isConnected) out.println("STATE ACTIVE"); });
        rbBusy.setOnAction(e -> { if(isConnected) out.println("STATE BUSY"); });
        rbAway.setOnAction(e -> { if(isConnected) out.println("STATE AWAY"); });
        
        box.getChildren().addAll(rbActive, rbBusy, rbAway);
        return box;
    }

    private TableView<Message> createChatTable() {
        chatTable = new TableView<>();
        chatTable.setStyle("-fx-border-color: #333; -fx-background-color: white;");
        TableColumn<Message, String> userCol = new TableColumn<>("User");
        userCol.setCellValueFactory(new PropertyValueFactory<>("user"));
        userCol.setPrefWidth(70);
        TableColumn<Message, String> msgCol = new TableColumn<>("Message");
        msgCol.setCellValueFactory(new PropertyValueFactory<>("message"));
        msgCol.setPrefWidth(180);
        TableColumn<Message, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("time"));
        timeCol.setPrefWidth(70);
        chatTable.getColumns().addAll(userCol, msgCol, timeCol);
        
        chatData = FXCollections.observableArrayList();
        filteredData = new FilteredList<>(chatData, p -> true);
        chatTable.setItems(filteredData);
        return chatTable;
    }

    private VBox createMessageInput() {
        VBox box = new VBox(5);
        targetRoomCombo = new ComboBox<>();
        targetRoomCombo.setPromptText("Select Room...");
        targetRoomCombo.setStyle("-fx-background-color: white; -fx-border-color: #333;");
        box.getChildren().add(targetRoomCombo);
        return box;
    }

    private VBox createPrivateMsgBox1() {
        VBox box = new VBox(5);
        pmCombo1 = new ComboBox<>(userNamesList);
        pmCombo1.setPrefWidth(120);
        pmCombo1.setStyle("-fx-background-color: white; -fx-border-color: #333;");
        HBox toBox = new HBox(5, new Label("To:"), pmCombo1);
        toBox.setAlignment(Pos.CENTER_LEFT);
        
        pmArea1 = new TextArea();
        pmArea1.setPrefRowCount(21);
        pmArea1.setEditable(false);
        pmArea1.setStyle("-fx-control-inner-background: white; -fx-border-color: #333;");
        
        TextField tf = new TextField();
        tf.setStyle("-fx-background-color: white; -fx-border-color: #333;");
        tf.setPrefWidth(130);
        Button sendBtn1 = new Button("SEND");
        styleButton(sendBtn1);
        
        sendBtn1.setOnAction(e -> {
            String target = pmCombo1.getValue();
            String msg = tf.getText().trim();
            if(target != null && !msg.isEmpty() && isConnected) {
                out.println("PM " + target + " " + msg);
                String time = new SimpleDateFormat("hh:mm a").format(new Date());
                pmArea1.appendText(time + " [Me -> " + target + "]: " + msg + "\n");
                tf.clear();
            }
        });
        
        HBox bottomBox = new HBox(5, tf, sendBtn1);
        box.getChildren().addAll(toBox, new Label("Message:"), pmArea1, bottomBox);
        return box;
    }

    public static class Message {
        private final SimpleStringProperty user, message, time;
        public Message(String user, String message, String time) {
            this.user = new SimpleStringProperty(user);
            this.message = new SimpleStringProperty(message);
            this.time = new SimpleStringProperty(time);
        }
        public String getUser() { return user.get(); }
        public String getMessage() { return message.get(); }
        public String getTime() { return time.get(); }
    }

    public static void main(String[] args) { launch(args); }
}