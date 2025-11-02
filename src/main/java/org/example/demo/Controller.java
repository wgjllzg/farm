package org.example.demo;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.util.Duration;

/**
 * Minimal JavaFX controller that mirrors last year's style while showing the new mechanics.
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

    private Game game;
    private ToggleButton[][] cells;
    private Timeline refreshTimeline;
    private String statusMessage = "Ready.";

    private int selectedRow = -1;
    private int selectedCol = -1;

    public void init(Game game) {
        this.game = game;
        createBoard();
        refreshBoard();
        startRefreshTicker();
    }

    private void createBoard() {
        gameBoard.getChildren().clear();
        cells = new ToggleButton[game.getRows()][game.getCols()];
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
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
        for (int row = 0; row < game.getRows(); row++) {
            for (int col = 0; col < game.getCols(); col++) {
                ToggleButton cell = cells[row][col];
                cell.setSelected(row == selectedRow && col == selectedCol);
                updateCellState(cell, row, col);
            }
        }
        updateCoins(statusMessage);
    }

    private void updateCellState(ToggleButton cell, int row, int col) {
        Game.PlotState state = game.getState(row, col);
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
        coinsLabel.setText("Coins: " + game.getCoins() + " | " + statusMessage);
    }

    @FXML
    private void handlePlant() {
        if (!ensureSelection()) {
            updateCoins("Select a plot first.");
            return;
        }
        try {
            game.plant(selectedRow, selectedCol);
            refreshBoard();
            updateCoins("Seeds planted! Matures in ~5s.");
            // TODO: 将本地操作改为向服务器发送“播种”请求，并等待服务器回传最新农田状态。
        } catch (Exception e) {
            updateCoins(e.getMessage());
        }
    }

    @FXML
    private void handleHarvest() {
        if (!ensureSelection()) {
            updateCoins("Select a plot first.");
            return;
        }
        try {
            game.harvest(selectedRow, selectedCol);
            refreshBoard();
            updateCoins("Harvest success!");
            // TODO: 改为触发服务器端“收获”指令，使用服务器返回的数据刷新 UI。
        } catch (Exception e) {
            updateCoins(e.getMessage());
        }
    }

    @FXML
    private void handleSteal() {
        game.stealRandom();
        refreshBoard();
        updateCoins("Simulated steal triggered.");
        // TODO: 替换为访问服务器、目标玩家，并处理并发偷菜返回结果。
    }

    public void shutdown() {
        if (refreshTimeline != null) {
            refreshTimeline.stop();
        }
        if (game != null) {
            game.shutdown();
        }
    }

    private boolean ensureSelection() {
        return selectedRow >= 0 && selectedCol >= 0;
    }

    private void startRefreshTicker() {
        refreshTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshBoard()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();
    }
}
