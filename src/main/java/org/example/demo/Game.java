package org.example.demo;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 农场的最小逻辑模型：服务器端使用。
 * 线程安全（大部分方法 synchronized），支持作物自动生长。
 */
public class Game {

    public enum PlotState {EMPTY, GROWING, RIPE}

    private static final int ROWS = 4;
    private static final int COLS = 4;
    private static final int PLANT_COST = 5;
    private static final int HARVEST_REWARD = 12;
    private static final int STEAL_REWARD = 4;

    private final PlotState[][] board = new PlotState[ROWS][COLS];
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "crop-growth");
        thread.setDaemon(true);
        return thread;
    });
    private final Random random = new Random();

    private int coins = 40;

    public Game() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                board[r][c] = PlotState.EMPTY;
            }
        }
    }

    public synchronized int getCoins() {
        return coins;
    }

    public synchronized PlotState getState(int row, int col) {
        return board[row][col];
    }

    /**
     * 服务器端：种植。线程安全。
     */
    public synchronized void plant(int row, int col) {
        if (board[row][col] != PlotState.EMPTY) {
            throw new IllegalStateException("Plot occupied");
        }
        if (coins < PLANT_COST) {
            throw new IllegalStateException("Not enough coins");
        }
        coins -= PLANT_COST;
        board[row][col] = PlotState.GROWING;

        // 模拟 5 秒后成熟
        scheduler.schedule(() -> {
            synchronized (Game.this) {
                if (board[row][col] == PlotState.GROWING) {
                    board[row][col] = PlotState.RIPE;
                }
            }
        }, 5, TimeUnit.SECONDS);
    }

    /**
     * 服务器端：收获。线程安全。
     */
    public synchronized void harvest(int row, int col) {
        if (board[row][col] != PlotState.RIPE) {
            throw new IllegalStateException("Crop not ripe");
        }
        board[row][col] = PlotState.EMPTY;
        coins += HARVEST_REWARD;
    }

    /**
     * 服务器端：简单模拟被偷逻辑（可后续改成真正多玩家偷菜）。
     */
    public synchronized void stealRandom() {
        int row = random.nextInt(ROWS);
        int col = random.nextInt(COLS);
        if (board[row][col] == PlotState.RIPE) {
            board[row][col] = PlotState.EMPTY;
            coins = Math.max(0, coins - STEAL_REWARD);
        }
    }

    public int getRows() {
        return ROWS;
    }

    public int getCols() {
        return COLS;
    }

    /**
     * 把当前状态编码成字符串：coins;rows;cols;cells
     * cells 是按行展开的 16 个字符：E/G/R
     */
    public synchronized String encodeState() {
        StringBuilder sb = new StringBuilder();
        sb.append(coins).append(";").append(ROWS).append(";").append(COLS).append(";");
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                char ch = switch (board[r][c]) {
                    case EMPTY -> 'E';
                    case GROWING -> 'G';
                    case RIPE -> 'R';
                };
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
