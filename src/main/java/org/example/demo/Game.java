package org.example.demo;

/** 纯视图缓存：不再本地推进生长，一切以服务端为准。 */
public class Game {

    public enum PlotState {EMPTY, GROWING, RIPE}

    private static final int ROWS = 4;
    private static final int COLS = 4;

    /** 当前界面正在显示的农场主人（可以是自己，也可以是好友） */
    private Player owner;

    /** 棋盘状态（始终是“当前 owner”农场的格子） */
    private final PlotState[][] board = new PlotState[ROWS][COLS];

    /** 自己的金币（coins 只表示本人金币，不表示当前 owner 的金币） */
    private int coins;

    public Game(Player selfPlayer) {
        this.owner = selfPlayer;
        this.coins = selfPlayer.getCoins();
        for (int r=0;r<ROWS;r++) {
            for (int c=0;c<COLS;c++) {
                board[r][c] = PlotState.EMPTY;
            }
        }
    }

    public Player getPlayer() {
        return owner;
    }

    /** 切换当前正在展示的农场主人（访问好友时使用） */
    public void setPlayer(Player owner) {
        this.owner = owner;
    }

    public int getRows() { return ROWS; }
    public int getCols() { return COLS; }

    public PlotState getState(int row, int col) {
        if (!inRange(row, col)) return PlotState.EMPTY;
        return board[row][col];
    }

    public void setCellState(int row, int col, PlotState state) {
        if (!inRange(row, col)) return;
        board[row][col] = state;
    }

    /** 自己的金币（UI 始终显示自己的金币） */
    public int getCoins() { return coins; }

    /** 从服务端同步自己的金币（比如收获成功、成熟推送等） */
    public void setCoinsFromServer(int coins) {
        this.coins = coins;
    }

    /**
     * 登录 / 回到自己农场：用完整快照覆盖当前棋盘，并更新“自己的金币”。
     * 注意：这里 coins 是“本人金币”，即使 owner 也是自己。
     */
    public void applySnapshot(int rows, int cols, String[] cells, int coins) {
        // 假设 rows/cols 恒为 4x4
        int idx = 0;
        for (int r=0;r<ROWS;r++){
            for (int c=0;c<COLS;c++){
                if (idx < cells.length) {
                    board[r][c] = PlotState.valueOf(cells[idx++]);
                } else {
                    board[r][c] = PlotState.EMPTY;
                }
            }
        }
        setCoinsFromServer(coins);
    }

    /**
     * 访问好友农场时使用：只更新棋盘，不改动金币（金币只表示自己）。
     */
    public void applyBoardSnapshot(int rows, int cols, String[] cells) {
        int idx = 0;
        for (int r=0;r<ROWS;r++){
            for (int c=0;c<COLS;c++){
                if (idx < cells.length) {
                    board[r][c] = PlotState.valueOf(cells[idx++]);
                } else {
                    board[r][c] = PlotState.EMPTY;
                }
            }
        }
    }

    public void shutdown() {
        // no-op
    }

    private boolean inRange(int r, int c) {
        return r >= 0 && r < ROWS && c >= 0 && c < COLS;
    }
}
