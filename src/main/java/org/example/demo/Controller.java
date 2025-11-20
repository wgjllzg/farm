package org.example.demo;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
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

    // 好友面板
    @FXML private VBox friendsPane;
    @FXML private TextField friendSearchField;
    @FXML private Button friendAddButton;
    @FXML private ListView<String> friendsList;

    // ====== 本地状态 ======
    Game game;
    private Player player;
    private ToggleButton[][] cells;
    private Timeline refreshTimeline;
    private String statusMessage = "Ready.";
    private int selectedRow = -1, selectedCol = -1;

    // ====== 长连接（统一登录/注册/种植/收获/推送/好友） ======
    private final LongLink longLink = new LongLink();
    String session = "";

    // === 提供给 Application 调用 ===
    public void connect(String host, int port) throws IOException { longLink.connect(host, port); }

    public CompletableFuture<JsonNode> signup(String username, String password) {
        return longLink.call("SIGNUP", Map.of("username", username, "password", password));
    }

    public CompletableFuture<JsonNode> login(String username, String password) {
        return longLink.call("LOGIN", Map.of("username", username, "password", password));
    }

    /** 初始化（在登录成功、设置好 player 后调用） */
    public void init(Game game) { init(game, null); }

    public void init(Game game, String session) {
        this.game = game;
        this.player = game.getPlayer();
        this.session = session == null ? "" : session;

        if (friendSearchField != null && friendAddButton != null) {
            friendAddButton.setDisable(true);
            friendSearchField.textProperty().addListener((o, ov, nv) ->
                    friendAddButton.setDisable(nv == null || !nv.matches("\\d+")));
        }

        createBoard();
        setPlayerInfo(player.getName(), player.getId());
        refreshBoard();
        startRefreshTicker();
    }

    /**
     * 登录后由 Application 调用，应用服务端返回的农场快照并刷新 UI。
     */
    public void applySnapshotFromServer(int rows, int cols, String[] cells, int coins) {
        if (game == null) return;
        game.applySnapshot(rows, cols, cells, coins);
        selectedRow = -1;
        selectedCol = -1;
        refreshBoard();
    }

    /**
     * 登录后由 Application 调用：从服务器加载好友列表，显示到右侧 ListView。
     */
    public void loadFriendsFromServer() {
        if (player == null) return;
        longLink.call("LIST_FRIENDS", Map.of(
                "playerId", player.getId(),
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
        for (int r = 0; r < game.getRows(); r++) {
            for (int c = 0; c < game.getCols(); c++) {
                ToggleButton cell = cells[r][c];
                cell.setSelected(r == selectedRow && c == selectedCol);
                updateCellState(cell, r, c);
            }
        }
        updateCoins(statusMessage);
        setPlayerInfo(player.getName(), player.getId());
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

    public void setPlayerInfo(String name, int id) {
        if (playerNameLabel != null) playerNameLabel.setText("Player: " + name);
        if (playerIdLabel != null)   playerIdLabel.setText("ID: " + id);
    }

    // ====== 按钮事件 ======
    @FXML
    private void handlePlant() {
        if (!ensureSelection()) { updateCoins("Select a plot first."); return; }
        if (!longLink.isConnected()) { updateCoins("Not connected."); return; }

        int r = selectedRow, c = selectedCol;
        String rid = UUID.randomUUID().toString();

        longLink.call("PLANT", Map.of(
                "playerId", player.getId(),
                "row", r, "col", c,
                "session", session
        ), rid).whenComplete((resp, err) -> Platform.runLater(() -> {
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
            String ps = resp.path("plotState").asText(null);
            if (ps != null) game.setCellState(r, c, Game.PlotState.valueOf(ps));
            refreshBoard();
            updateCoins("Seeds planted!");
        }));
    }

    @FXML
    private void handleHarvest() {
        if (!ensureSelection()) { updateCoins("Select a plot first."); return; }
        if (!longLink.isConnected()) { updateCoins("Not connected."); return; }

        int r = selectedRow, c = selectedCol;
        String rid = UUID.randomUUID().toString();

        longLink.call("HARVEST", Map.of(
                "playerId", player.getId(),
                "row", r, "col", c,
                "session", session
        ), rid).whenComplete((resp, err) -> Platform.runLater(() -> {
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
            String ps = resp.path("plotState").asText(null);
            if (ps != null) game.setCellState(r, c, Game.PlotState.valueOf(ps));
            refreshBoard();
            updateCoins("Harvest success!");
        }));
    }

    @FXML
    private void handleSteal() {
        // 以后实现 Step3
        updateCoins("Steal: TODO (friends system).");
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

    /** 实际执行 ADD_FRIEND 协议 */
    private void addFriendById(int targetId) {
        if (!longLink.isConnected()) {
            updateCoins("Not connected.");
            return;
        }
        longLink.call("ADD_FRIEND", Map.of(
                "playerId", player.getId(),
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
            socket.setSoTimeout(0); // 长连
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
            if (controller == null || controller.game == null) return;
            int row  = n.path("row").asInt(-1);
            int col  = n.path("col").asInt(-1);
            String ps = n.path("plotState").asText(null);
            int coins = n.path("coins").asInt(controller.game.getCoins());
            if (row < 0 || col < 0 || ps == null) return;

            Platform.runLater(() -> {
                controller.game.setCoinsFromServer(coins);
                controller.game.setCellState(row, col, Game.PlotState.valueOf(ps));
                controller.refreshBoard();
            });
        }
    }

    public Controller() { PushHandlers.bind(this); }
}
