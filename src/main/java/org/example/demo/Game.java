package org.example.demo;

/** 纯视图缓存：不再本地推进生长或修改金币，一切以服务端为准。 */
public class Game {

    public enum PlotState {EMPTY, GROWING, RIPE}

    private static final int ROWS = 4;
    private static final int COLS = 4;

    private final Player player;
    private final PlotState[][] board = new PlotState[ROWS][COLS];
    private int coins;

    public Game(Player player) {
        this.player = player;
        this.coins = player.getCoins();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                board[r][c] = PlotState.EMPTY;
            }
        }
    }

    public synchronized int getCoins() { return coins; }

    public synchronized void setCoinsFromServer(int newCoins) {
        this.coins = newCoins;
        // 顺便同步 Player 实例，便于其它地方直接用 player.getCoins()
        player.setCoins(newCoins);
    }

    public synchronized PlotState getState(int row, int col) { return board[row][col]; }

    public synchronized void setCellState(int row, int col, PlotState st) {
        board[row][col] = st;
    }

    public int getRows() { return ROWS; }

    public int getCols() { return COLS; }

    public Player getPlayer() { return player; }

    /**
     * 全量快照应用（登录成功或 GET_FARM 之后调用）。
     * rows/cols 来自服务端，目前总是 4x4，这里做一下安全裁剪。
     */
    public synchronized void applySnapshot(int rows, int cols, String[] cells, int coins) {
        int idx = 0;
        int maxRows = Math.min(rows, ROWS);
        int maxCols = Math.min(cols, COLS);

        for (int r = 0; r < maxRows; r++) {
            for (int c = 0; c < maxCols; c++) {
                PlotState st = PlotState.EMPTY;
                if (idx < cells.length && cells[idx] != null) {
                    st = PlotState.valueOf(cells[idx]);
                }
                board[r][c] = st;
                idx++;
            }
        }
        // 超出 rows/cols 的格子保持默认 EMPTY 即可

        setCoinsFromServer(coins);
    }

    public void shutdown() {
        // 目前没有资源需要释放，预留
    }
}
