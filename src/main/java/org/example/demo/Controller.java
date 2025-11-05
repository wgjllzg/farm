package org.example.demo;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.util.Duration;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaFX 控制器：负责 UI 与服务器之间的交互。
 */
public class Controller {

    @FXML
    private GridPane gameBoard;

    @FXML
    private Label coinsLabel;

    @FXML
    private Button plantButton;

    @FXML
    private Button harvestButton;

    @FXML
    private Button stealButton;

    // 本地缓存的农场视图（从服务器同步）
    private ToggleButton[][] cells;
    private Timeline refreshTimeline;
    private String statusMessage = "Ready.";

    private int selectedRow = -1;
    private int selectedCol = -1;

    private int rows = 4;
    private int cols = 4;
    private Game.PlotState[][] board;
    private int coins;

    // 网络相关
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private ExecutorService networkExecutor;

    /**
     * 客户端初始化：连接服务器，完成 HELLO 握手，并获取初始农场状态。
     */
    public void init(String host, int port, String playerName) {
        try {
            socket = new Socket(host, port);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            networkExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "client-network");
                t.setDaemon(true);
                return t;
            });

            // 发送 HELLO，服务器创建或加载该玩家的农场
            out.println("HELLO " + playerName);
            String line = in.readLine();
            if (line != null && line.startsWith("STATE ")) {
                handleStateLine(line);
            } else {
                statusMessage = "Failed to receive initial state.";
            }
        } catch (IOException e) {
            statusMessage = "Failed to connect: " + e.getMessage();
        }

        if (board == null) {
            // 如果没拿到状态，就先初始化一个空板，避免 NPE
            board = new Game.PlotState[rows][cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    board[r][c] = Game.PlotState.EMPTY;
                }
            }
        }

        createBoard();
        refreshBoard();
        startRefreshTicker();
    }

    private void createBoard() {
        gameBoard.getChildren().clear();
        cells = new ToggleButton[rows][cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                ToggleButton cell = new ToggleButton();
                cell.setPrefSize(60, 60);
                cell.getStyleClass().add("plot-button");
                int r = row;
                int c = col;
                cell.setOnAction(event -> {
                    selectedRow = r;
                    selectedCol = c;
                    refreshBoard();
                });
                gameBoard.add(cell, col, row);
                cells[row][col] = cell;
            }
        }
    }

    private void refreshBoard() {
        if (cells == null || board == null) return;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                ToggleButton cell = cells[row][col];
                cell.setSelected(row == selectedRow && col == selectedCol);
                updateCellState(cell, row, col);
            }
        }
        updateCoins(statusMessage);
    }

    private void updateCellState(ToggleButton cell, int row, int col) {
        Game.PlotState state = board[row][col];
        cell.getStyleClass().removeAll("state-empty", "state-growing", "state-ripe");
        cell.setText(switch (state) {
            case EMPTY -> "Empty";
            case GROWING -> "Growing";
            case RIPE -> "Ripe";
        });
        switch (state) {
            case EMPTY -> cell.getStyleClass().add("state-empty");
            case GROWING -> cell.getStyleClass().add("state-growing");
            case RIPE -> cell.getStyleClass().add("state-ripe");
        }
    }

    private void updateCoins(String message) {
        statusMessage = message;
        coinsLabel.setText("Coins: " + coins + " | " + statusMessage);
    }

    @FXML
    private void handlePlant() {
        if (!ensureSelection()) {
            updateCoins("Select a plot first.");
            return;
        }
        if (!isConnected()) {
            updateCoins("Not connected to server.");
            return;
        }
        sendCommand("PLANT " + selectedRow + " " + selectedCol,
                "Planting...");
    }

    @FXML
    private void handleHarvest() {
        if (!ensureSelection()) {
            updateCoins("Select a plot first.");
            return;
        }
        if (!isConnected()) {
            updateCoins("Not connected to server.");
            return;
        }
        sendCommand("HARVEST " + selectedRow + " " + selectedCol,
                "Harvesting...");
    }

    @FXML
    private void handleSteal() {
        if (!isConnected()) {
            updateCoins("Not connected to server.");
            return;
        }
        // 当前 demo 版使用服务器端 Game.stealRandom 模拟被偷
        sendCommand("STEAL", "Trying to steal...");
    }

    public void shutdown() {
        if (refreshTimeline != null) {
            refreshTimeline.stop();
        }
        if (networkExecutor != null) {
            networkExecutor.shutdownNow();
        }
        closeConnection();
    }

    private boolean ensureSelection() {
        return selectedRow >= 0 && selectedCol >= 0;
    }

    private boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void startRefreshTicker() {
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            if (isConnected() && networkExecutor != null) {
                networkExecutor.submit(this::requestStateFromServer);
            }
        }));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
    }

    private void requestStateFromServer() {
        try {
            synchronized (this) {
                if (out != null) {
                    out.println("STATE");
                } else {
                    return;
                }
            }
            String line = in.readLine();
            if (line != null && line.startsWith("STATE ")) {
                handleStateLine(line);
                Platform.runLater(this::refreshBoard);
            }
        } catch (IOException e) {
            Platform.runLater(() -> updateCoins("Disconnected: " + e.getMessage()));
            closeConnection();
        }
    }

    /**
     * 统一发送命令：在网络线程中执行，避免阻塞 UI。
     */
    private void sendCommand(String command, String busyMessage) {
        updateCoins(busyMessage);
        if (networkExecutor == null) return;
        networkExecutor.submit(() -> {
            try {
                synchronized (this) {
                    if (out != null) {
                        out.println(command);
                    } else {
                        return;
                    }
                }
                String line = in.readLine();
                if (line != null && line.startsWith("STATE ")) {
                    handleStateLine(line);
                    Platform.runLater(this::refreshBoard);
                }
            } catch (IOException e) {
                Platform.runLater(() -> updateCoins("Network error: " + e.getMessage()));
                closeConnection();
            }
        });
    }

    /**
     * 解析服务器返回的 STATE 行：
     * 格式：STATE message;coins;rows;cols;cells
     */
    private void handleStateLine(String line) {
        try {
            String payload = line.substring("STATE ".length());
            String[] parts = payload.split(";", 5);
            if (parts.length < 5) {
                statusMessage = "Malformed state from server.";
                return;
            }
            statusMessage = parts[0];
            coins = Integer.parseInt(parts[1]);
            rows = Integer.parseInt(parts[2]);
            cols = Integer.parseInt(parts[3]);
            String cellsStr = parts[4];

            if (board == null || board.length != rows || board[0].length != cols) {
                board = new Game.PlotState[rows][cols];
            }

            int idx = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    char ch = cellsStr.charAt(idx++);
                    Game.PlotState state = switch (ch) {
                        case 'E' -> Game.PlotState.EMPTY;
                        case 'G' -> Game.PlotState.GROWING;
                        case 'R' -> Game.PlotState.RIPE;
                        default -> Game.PlotState.EMPTY;
                    };
                    board[r][c] = state;
                }
            }
        } catch (Exception e) {
            statusMessage = "Error parsing state: " + e.getMessage();
        }
    }

    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
