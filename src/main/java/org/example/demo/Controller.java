package org.example.demo;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class Controller {

    // ====== UI ======
    @FXML private GridPane gameBoard;
    @FXML private Label playerNameLabel;
    @FXML private Label playerIdLabel;
    @FXML private Label coinsLabel;

    @FXML private Button plantButton;
    @FXML private Button harvestButton;
    @FXML private Button stealButton;
    @FXML private Button myFarmButton;

    @FXML private VBox friendsPane;
    @FXML private TextField friendSearchField;
    @FXML private Button friendAddButton;
    @FXML private ListView<String> friendsList;

    // ====== 本地状态 ======
    /** 当前登录玩家（我自己） */
    Player selfPlayer;
    /** 当前界面正在显示的农场视图（owner 可以是自己或好友） */
    Game game;

    private ToggleButton[][] cells;
    private Timeline refreshTimeline;
    private String statusMessage = "Ready.";
    private int selectedRow = -1, selectedCol = -1;

    /** 当前界面正在看的农场主人 id */
    int currentOwnerId;
    /** 当前正在看的农场主是否在线（仅好友 farm 时有意义） */
    private boolean currentOwnerOnline = false;
    /** 当前农场这一轮是否还有偷菜额度 */
    private boolean currentOwnerCanSteal = false;

    // ====== 长连接 ======
    private final LongLink longLink = new LongLink();
    private String session = "";

    // === 提供给 Application 调用 ===
    public void connect(String host, int port) throws IOException { longLink.connect(host, port); }

    public CompletableFuture<JsonNode> signup(String username, String password) {
        return longLink.call("SIGNUP", Map.of("username", username, "password", password));
    }

    public CompletableFuture<JsonNode> login(String username, String password) {
        return longLink.call("LOGIN", Map.of("username", username, "password", password));
    }

    public void init(Game game) { init(game, null); }

    public void init(Game game, String session) {
        this.game = game;
        this.selfPlayer = game.getPlayer();
        this.session = session == null ? "" : session;
        this.currentOwnerId = selfPlayer.getId();
        this.currentOwnerOnline = false;
        this.currentOwnerCanSteal = false;

        if (friendSearchField != null && friendAddButton != null) {
            friendAddButton.setDisable(true);
            friendSearchField.textProperty().addListener((o, ov, nv) ->
                    friendAddButton.setDisable(nv == null || !nv.matches("\\d+")));
        }

        if (friendsList != null) {
            friendsList.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    String item = friendsList.getSelectionModel().getSelectedItem();
                    if (item != null) {
                        Integer targetId = parseIdFromFriendItem(item);
                        if (targetId != null) {
                            visitFarm(targetId);
                        }
                    }
                }
            });
        }

        createBoard();
        setPlayerInfoLabels();
        updateButtonsForOwnFarm();
        refreshBoard();
        startRefreshTicker();
    }

    /** 登录后应用“自己农场”快照并刷新 UI */
    public void applySnapshotFromServer(int rows, int cols, String[] cells, int coins) {
        if (game == null) return;
        game.setPlayer(selfPlayer);
        currentOwnerId = selfPlayer.getId();
        currentOwnerOnline = false;
        currentOwnerCanSteal = false;

        game.applySnapshot(rows, cols, cells, coins);
        selfPlayer.setCoins(coins);

        selectedRow = -1;
        selectedCol = -1;
        setPlayerInfoLabels();
        updateButtonsForOwnFarm();
        refreshBoard();
    }

    /** 登录后加载好友列表 */
    public void loadFriendsFromServer() {
        if (selfPlayer == null) return;
        longLink.call("LIST_FRIENDS", Map.of(
                "playerId", selfPlayer.getId(),
                "session", session
        )).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) {
                updateCoins("Load friends error: " + err.getMessage());
                return;
            }
            boolean ok = resp.path("ok").asBoolean(false);
            if (!ok) {
                String msg = resp.path("msg").asText("list friends failed");
                updateCoins(msg);
                return;
            }
            if (friendsList != null) {
                friendsList.getItems().clear();
                JsonNode arr = resp.path("friends");
                if (arr != null && arr.isArray()) {
                    for (JsonNode f : arr) {
                        int id = f.path("id").asInt();
                        String name = f.path("name").asText("?");
                        friendsList.getItems().add(name + " (ID: " + id + ")");
                    }
                }
            }
            updateCoins("Friends loaded.");
        }));
    }

    // ====== 访问农场：自己 / 好友 ======
    private void visitFarm(int targetId) {
        if (!longLink.isConnected()) {
            updateCoins("Not connected.");
            return;
        }
        longLink.call("VISIT_FARM", Map.of(
                "playerId", selfPlayer.getId(),
                "targetId", targetId,
                "session", session
        )).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) {
                updateCoins("Visit farm error: " + err.getMessage());
                return;
            }
            if (!resp.path("ok").asBoolean(false)) {
                updateCoins(resp.path("msg").asText("visit failed"));
                return;
            }
            int ownerId = resp.path("targetId").asInt(targetId);
            String ownerName = resp.path("targetName").asText("Unknown");
            int rows = resp.path("rows").asInt(4);
            int cols = resp.path("cols").asInt(4);
            JsonNode cellsNode = resp.path("cells");
            String[] cellsArr;
            if (cellsNode != null && cellsNode.isArray()) {
                cellsArr = new String[cellsNode.size()];
                for (int i = 0; i < cellsNode.size(); i++) {
                    cellsArr[i] = cellsNode.get(i).asText("EMPTY");
                }
            } else {
                cellsArr = new String[0];
            }

            boolean ownerOnline = resp.path("ownerOnline").asBoolean(false);
            boolean canSteal = resp.path("canSteal").asBoolean(false);
            currentOwnerOnline = ownerOnline;
            currentOwnerCanSteal = canSteal;

            Player owner = new Player();
            owner.setId(ownerId);
            owner.setName(ownerName);

            game.setPlayer(owner);
            currentOwnerId = ownerId;

            if (ownerId == selfPlayer.getId()) {
                int coins = resp.path("coins").asInt(game.getCoins());
                game.applySnapshot(rows, cols, cellsArr, coins);
                selfPlayer.setCoins(coins);
                updateButtonsForOwnFarm();
                updateCoins("Back to my farm.");
            } else {
                game.applyBoardSnapshot(rows, cols, cellsArr);
                updateButtonsForVisitingFriend();
                String onlineStr = ownerOnline ? "(online)" : "(offline)";
                updateCoins("Viewing " + ownerName + "'s farm " + onlineStr);
            }

            selectedRow = -1;
            selectedCol = -1;
            setPlayerInfoLabels();
            refreshBoard();
        }));
    }

    @FXML
    private void handleBackToMyFarm() {
        if (selfPlayer != null) {
            visitFarm(selfPlayer.getId());
        }
    }

    // ====== 棋盘与渲染 ======
    private void createBoard() {
        gameBoard.getChildren().clear();
        cells = new ToggleButton[game.getRows()][game.getCols()];
        for (int r = 0; r < game.getRows(); r++) {
            for (int c = 0; c < game.getCols(); c++) {
                ToggleButton cell = new ToggleButton();
                cell.setPrefSize(60, 60);
                cell.getStyleClass().add("plot-button");
                final int rr = r, cc = c;
                cell.setOnAction(e -> { selectedRow = rr; selectedCol = cc; refreshBoard(); });
                gameBoard.add(cell, c, r);
                cells[r][c] = cell;
            }
        }
    }

    private void refreshBoard() {
        if (game == null || cells == null) return;
        for (int r = 0; r < game.getRows(); r++) {
            for (int c = 0; c < game.getCols(); c++) {
                ToggleButton cell = cells[r][c];
                cell.setSelected(r == selectedRow && c == selectedCol);
                updateCellState(cell, r, c);
            }
        }
        updateCoins(statusMessage);
        setPlayerInfoLabels();
    }

    private void updateCellState(ToggleButton cell, int row, int col) {
        Game.PlotState s = game.getState(row, col);
        cell.getStyleClass().removeAll("state-empty", "state-growing", "state-ripe");
        cell.setText(switch (s) {
            case EMPTY -> "Empty";
            case GROWING -> "Growing";
            case RIPE -> "Ripe";
        });
        switch (s) {
            case EMPTY -> cell.getStyleClass().add("state-empty");
            case GROWING -> cell.getStyleClass().add("state-growing");
            case RIPE -> cell.getStyleClass().add("state-ripe");
        }
    }

    private void updateCoins(String msg) {
        statusMessage = msg;
        coinsLabel.setText("Coins: " + game.getCoins() + " | " + statusMessage);
    }

    private void setPlayerInfoLabels() {
        if (selfPlayer == null || game == null) return;
        Player owner = game.getPlayer();
        if (owner != null && owner.getId() != selfPlayer.getId()) {
            String onlineStr = currentOwnerOnline ? " (online)" : " (offline)";
            playerNameLabel.setText("Viewing: " + owner.getName() + "'s Farm" + onlineStr);
            playerIdLabel.setText("Me: " + selfPlayer.getName() + " (ID: " + selfPlayer.getId() + ")");
        } else {
            playerNameLabel.setText("Player: " + selfPlayer.getName());
            playerIdLabel.setText("ID: " + selfPlayer.getId());
        }
    }

    // ====== 按钮事件 ======
    @FXML
    private void handlePlant() {
        if (!ensureSelection()) { updateCoins("Select a plot first."); return; }
        if (!longLink.isConnected()) { updateCoins("Not connected."); return; }
        if (game.getPlayer().getId() != selfPlayer.getId()) {
            updateCoins("You can only plant in your own farm.");
            return;
        }

        int r = selectedRow, c = selectedCol;

        longLink.call("PLANT", Map.of(
                "playerId", selfPlayer.getId(),
                "row", r, "col", c,
                "session", session
        )).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) {
                updateCoins("Network error: " + err.getMessage());
                return;
            }
            boolean ok = resp.path("ok").asBoolean(false);
            if (!ok) {
                String msg = resp.path("msg").asText("plant failed");
                updateCoins(msg);
                return;
            }
            int coins = resp.path("coins").asInt(game.getCoins());
            game.setCoinsFromServer(coins);
            selfPlayer.setCoins(coins);
            String ps = resp.path("plotState").asText(null);
            if (ps != null && currentOwnerId == selfPlayer.getId()) {
                game.setCellState(r, c, Game.PlotState.valueOf(ps));
            }
            refreshBoard();
            updateCoins("Seeds planted!");
        }));
    }

    @FXML
    private void handleHarvest() {
        if (!ensureSelection()) { updateCoins("Select a plot first."); return; }
        if (!longLink.isConnected()) { updateCoins("Not connected."); return; }
        if (game.getPlayer().getId() != selfPlayer.getId()) {
            updateCoins("You can only harvest in your own farm.");
            return;
        }

        int r = selectedRow, c = selectedCol;

        longLink.call("HARVEST", Map.of(
                "playerId", selfPlayer.getId(),
                "row", r, "col", c,
                "session", session
        )).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) {
                updateCoins("Network error: " + err.getMessage());
                return;
            }
            boolean ok = resp.path("ok").asBoolean(false);
            if (!ok) {
                String msg = resp.path("msg").asText("harvest failed");
                updateCoins(msg);
                return;
            }
            int coins = resp.path("coins").asInt(game.getCoins());
            game.setCoinsFromServer(coins);
            selfPlayer.setCoins(coins);
            String ps = resp.path("plotState").asText(null);
            if (ps != null && currentOwnerId == selfPlayer.getId()) {
                game.setCellState(r, c, Game.PlotState.valueOf(ps));
            }
            refreshBoard();
            updateCoins("Harvest success!");
        }));
    }

    @FXML
    private void handleSteal() {
        if (!ensureSelection()) { updateCoins("Select a plot first."); return; }
        if (!longLink.isConnected()) { updateCoins("Not connected."); return; }

        // 自己家不能偷
        if (game.getPlayer().getId() == selfPlayer.getId()) {
            updateCoins("You cannot steal in your own farm.");
            return;
        }

        // 对方在线 / 没有额度：按钮应该已经灰掉，这里再次兜底
        if (currentOwnerOnline) {
            updateCoins("Owner is online, cannot steal.");
            currentOwnerCanSteal = false;
            updateButtonsForVisitingFriend();
            return;
        }
        if (!currentOwnerCanSteal) {
            updateCoins("This farm cannot be stolen anymore.");
            updateButtonsForVisitingFriend();
            return;
        }

        int r = selectedRow, c = selectedCol;
        int ownerId = game.getPlayer().getId();

        longLink.call("STEAL", Map.of(
                "playerId", selfPlayer.getId(),
                "targetId", ownerId,
                "row", r, "col", c,
                "session", session
        )).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) {
                updateCoins("Steal error: " + err.getMessage());
                return;
            }
            boolean ok = resp.path("ok").asBoolean(false);
            if (!ok) {
                String msg = resp.path("msg").asText("steal failed");
                updateCoins(msg);

                String lower = msg.toLowerCase();
                if (lower.contains("owner online")) {
                    currentOwnerOnline = true;
                    currentOwnerCanSteal = false;
                } else if (lower.contains("already stolen") || lower.contains("25%")) {
                    currentOwnerCanSteal = false;
                }
                updateButtonsForVisitingFriend();
                return;
            }

            int thiefCoins = resp.path("coins").asInt(game.getCoins());
            game.setCoinsFromServer(thiefCoins);
            selfPlayer.setCoins(thiefCoins);

            // 服务器会在 resp 中给出这一轮是否还能继续偷
            if (resp.has("canSteal")) {
                currentOwnerCanSteal = resp.path("canSteal").asBoolean(false);
            }

            // 地块变化由 PUSH_CELL_UPDATE 统一更新；这里不强行改
            updateButtonsForVisitingFriend();
            refreshBoard();
            updateCoins("Steal success!");
        }));
    }

    @FXML
    private void handleAddFriend() {
        if (friendSearchField == null) return;
        String text = friendSearchField.getText();
        if (text == null || text.isEmpty()) return;
        if (!text.matches("\\d+")) {
            updateCoins("Friend ID must be a number.");
            return;
        }
        int targetId = Integer.parseInt(text);
        addFriendById(targetId);
    }

    private void addFriendById(int targetId) {
        if (!longLink.isConnected()) {
            updateCoins("Not connected.");
            return;
        }
        longLink.call("ADD_FRIEND", Map.of(
                "playerId", selfPlayer.getId(),
                "targetId", targetId,
                "session", session
        )).whenComplete((resp, err) -> Platform.runLater(() -> {
            if (err != null) {
                updateCoins("Add friend error: " + err.getMessage());
                return;
            }
            boolean ok = resp.path("ok").asBoolean(false);
            if (!ok) {
                String msg = resp.path("msg").asText("add friend failed");
                updateCoins(msg);
                return;
            }
            int fid = resp.path("friendId").asInt(targetId);
            String fname = resp.path("friendName").asText("?");
            if (friendsList != null) {
                String label = fname + " (ID: " + fid + ")";
                if (!friendsList.getItems().contains(label)) {
                    friendsList.getItems().add(label);
                }
            }
            friendSearchField.clear();
            updateCoins("Friend added: " + fname + " (ID: " + fid + ")");
        }));
    }

    public void shutdown() {
        if (refreshTimeline != null) {
            refreshTimeline.stop();
        }
        longLink.close();
    }

    private boolean ensureSelection() { return selectedRow >= 0 && selectedCol >= 0; }

    private void startRefreshTicker() {
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshBoard()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
    }

    /** 当前在“自己家”时的按钮状态 */
    private void updateButtonsForOwnFarm() {
        plantButton.setDisable(false);
        harvestButton.setDisable(false);

        if (stealButton != null)  stealButton.setDisable(true);
        if (myFarmButton != null) myFarmButton.setDisable(true);
    }

    /** 当前在“好友家”时的按钮状态（根据在线&偷菜配额控制 Steal） */
    private void updateButtonsForVisitingFriend() {
        plantButton.setDisable(true);
        harvestButton.setDisable(true);

        if (myFarmButton != null) myFarmButton.setDisable(false);

        if (stealButton != null) {
            boolean enableSteal = !currentOwnerOnline && currentOwnerCanSteal;
            stealButton.setDisable(!enableSteal);
        }
    }

    private Integer parseIdFromFriendItem(String item) {
        int idx = item.lastIndexOf("ID:");
        if (idx < 0) return null;
        int start = idx + 3;
        int end = item.indexOf(")", start);
        if (end < 0) end = item.length();
        String num = item.substring(start, end).trim();
        if (!num.matches("\\d+")) return null;
        return Integer.parseInt(num);
    }

    // ====== 长连接实现 ======
    private static class LongLink {
        private final ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        private Socket socket;
        private BufferedWriter out;
        private BufferedReader in;
        private Thread readerThread;
        private final Object writeLock = new Object();
        private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> inflight = new ConcurrentHashMap<>();
        private volatile boolean connected = false;

        boolean isConnected() { return connected; }

        void connect(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setSoTimeout(0);
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            connected = true;

            readerThread = new Thread(this::readLoop, "push-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        CompletableFuture<JsonNode> call(String type, Map<String, ?> payload) {
            String rid = UUID.randomUUID().toString();
            return call(type, payload, rid);
        }

        CompletableFuture<JsonNode> call(String type, Map<String, ?> payload, String requestId) {
            if (!connected) {
                var f = new CompletableFuture<JsonNode>();
                f.completeExceptionally(new IOException("not connected"));
                return f;
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("type", type);
            node.put("requestId", requestId);
            if (payload != null) {
                payload.forEach((k, v) -> node.set(k, mapper.valueToTree(v)));
            }
            CompletableFuture<JsonNode> fut = new CompletableFuture<>();
            inflight.put(requestId, fut);

            try {
                String line = mapper.writeValueAsString(node);
                synchronized (writeLock) {
                    out.write(line);
                    out.write("\n");
                    out.flush();
                }
            } catch (IOException e) {
                inflight.remove(requestId);
                fut.completeExceptionally(e);
            }
            return fut;
        }

        private void readLoop() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    JsonNode n = mapper.readTree(line);
                    String typ = n.path("type").asText("");
                    if ("RESP".equalsIgnoreCase(typ)) {
                        String rid = n.path("requestId").asText(null);
                        if (rid != null) {
                            CompletableFuture<JsonNode> fut = inflight.remove(rid);
                            if (fut != null) {
                                fut.complete(n);
                            }
                        }
                    } else if ("PUSH_CELL_UPDATE".equalsIgnoreCase(typ)) {
                        PushHandlers.onCellUpdate(n);
                    } else {
                        System.out.println("[INFO] unknown push: " + line);
                    }
                }
            } catch (IOException e) {
                System.out.println("[INFO] readLoop ended: " + e.getMessage());
            } finally {
                connected = false;
                inflight.forEach((k, f) -> f.completeExceptionally(new IOException("connection closed")));
                inflight.clear();
                try { if (socket != null) socket.close(); } catch (Exception ignore) {}
            }
        }

        void close() {
            connected = false;
            try { if (socket != null) socket.close(); } catch (Exception ignore) {}
        }
    }

    // ====== 推送 -> UI ======
    private static class PushHandlers {
        private static Controller controller;

        static void bind(Controller c) { controller = c; }

        static void onCellUpdate(JsonNode n) {
            if (controller == null || controller.game == null || controller.selfPlayer == null) return;
            int ownerId = n.path("playerId").asInt(-1);
            int row  = n.path("row").asInt(-1);
            int col  = n.path("col").asInt(-1);
            String ps = n.path("plotState").asText(null);
            int coins = n.path("coins").asInt(controller.game.getCoins());
            if (row < 0 || col < 0 || ps == null) return;

            Platform.runLater(() -> {
                // 如果是我自己的农场更新：无论当前在看谁，都更新我的金币
                if (ownerId == controller.selfPlayer.getId()) {
                    controller.game.setCoinsFromServer(coins);
                    controller.selfPlayer.setCoins(coins);
                }
                // 如果当前正在看的正是这个农场，才更新棋盘
                if (ownerId == controller.currentOwnerId) {
                    controller.game.setCellState(row, col, Game.PlotState.valueOf(ps));
                }
                controller.refreshBoard();
            });
        }
    }

    public Controller() { PushHandlers.bind(this); }
}
