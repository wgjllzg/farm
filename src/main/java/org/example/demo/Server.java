package org.example.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {

    // ===== 协议与模型 =====
    public enum ReqType { LOGIN, SIGNUP, PLANT, HARVEST, PING, ADD_FRIEND, LIST_FRIENDS }
    public enum PlotState { EMPTY, GROWING, RIPE }

    /** 统一响应外壳（同一条长连上的 request/response） */
    static class RespShell {
        public String type = "RESP";
        public String requestId;
        public boolean ok;
        public String msg;

        // 常用负载
        public Integer playerId;
        public Integer coins;
        public String session;
        public String playerName;
        public Integer row, col;
        public String plotState;

        // ★ 农场快照字段（登录成功时一起返回）
        public Integer rows;
        public Integer cols;
        public List<String> cells;  // 长度 = rows * cols，按行展开

        // ★ 好友相关字段
        public Integer friendId;      // 新增的好友 id（ADD_FRIEND）
        public String friendName;     // 新增的好友名字
        public List<FriendInfo> friends; // 好友列表（LIST_FRIENDS）

    }

    /** ★ 好友信息（LIST_FRIENDS 返回用） */
    static class FriendInfo {
        public int id;
        public String name;
        public FriendInfo() {}
        public FriendInfo(int id, String name) { this.id = id; this.name = name; }
    }

    /** 主动推送：单格更新（成熟/收获） */
    static class PushCellUpdate {
        public String type = "PUSH_CELL_UPDATE";
        public Integer playerId;
        public Integer row, col;
        public String plotState; // EMPTY/GROWING/RIPE
        public Integer coins;

        public PushCellUpdate() {}
        public PushCellUpdate(int pid, int r, int c, PlotState ps, int coins){
            this.playerId = pid; this.row = r; this.col = c;
            this.plotState = ps.name(); this.coins = coins;
        }
    }

    /** 每个玩家一块 4x4 农场（内存） */
    static class Farm {
        final int rows = 4, cols = 4;
        final PlotState[][] board = new PlotState[rows][cols];
        final Long[][] ripeAt = new Long[rows][cols]; // 仅当 GROWING 时有预计成熟时间
        Farm() {
            for (int r=0;r<rows;r++) {
                for (int c=0;c<cols;c++) {
                    board[r][c] = PlotState.EMPTY;
                    ripeAt[r][c] = null;
                }
            }
        }
    }

    // ===== 服务器字段 =====
    private final int port;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /** username(lowercase) -> Player */
    private final Map<String, Player> players = new ConcurrentHashMap<>();
    /** playerId -> Player */
    private final Map<Integer, Player> playersById = new ConcurrentHashMap<>();
    /** playerId -> Farm */
    private final Map<Integer, Farm> farms = new ConcurrentHashMap<>();
    /** playerId -> 长连连接，用于主动推送 */
    private final Map<Integer, ClientConn> conns = new ConcurrentHashMap<>();
    /** ★ playerId -> 好友集合（双向存储） */
    private final Map<Integer, Set<Integer>> friends = new ConcurrentHashMap<>();

    private final AtomicInteger nextId = new AtomicInteger(1);

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

    // 持久化文件
    private static final Path DATA_DIR       = Paths.get("data");
    private static final Path PLAYERS_FILE   = DATA_DIR.resolve("players.json");
    private static final Path FARMS_FILE     = DATA_DIR.resolve("farms.json");
    private static final Path FRIENDS_FILE   = DATA_DIR.resolve("friends.json"); // ★ 新增好友文件
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor();

    public Server(int port) { this.port = port; }

    // ===== 启动 =====
    public void start() throws IOException {
        loadPlayersFromDisk();
        loadFarmsFromDisk();
        loadFriendsFromDisk();  // ★ 加载好友

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flushPlayersSync();
            flushFarmsSync();
            flushFriendsSync();
        }));

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Server listening on " + port);
            while (true) {
                Socket s = ss.accept();
                // 不因读超时断开；若要心跳踢死连接可改为 60_000 并让客户端定期 PING
                s.setSoTimeout(0);
                pool.submit(() -> handleLongConn(s));
            }
        }
    }

    // ===== 每个客户端一个“长连”循环 =====
    private void handleLongConn(Socket s) {
        ClientConn conn = null;
        try (s;
             BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            conn = new ClientConn(s, out);

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // —— 日志：收到原始指令（不脱敏，应你需求）——
                System.out.println("[RECV] " + line);

                JsonNode node = mapper.readTree(line);
                String requestId = optText(node, "requestId");
                String type = optText(node, "type");
                if (type == null) {
                    RespShell bad = new RespShell();
                    bad.requestId = requestId; // 可能为 null
                    bad.ok = false; bad.msg = "bad request: missing type";
                    String outJson = mapper.writeValueAsString(bad);
                    safeWrite(out, outJson);
                    System.out.println("[SEND] " + outJson);
                    continue;
                }

                if ("PING".equalsIgnoreCase(type)) {
                    RespShell resp = new RespShell();
                    resp.requestId = requestId;
                    resp.ok = true; resp.msg = "pong";
                    String outJson = mapper.writeValueAsString(resp);
                    safeWrite(out, outJson);
                    System.out.println("[SEND] " + outJson);
                    continue;
                }

                ReqType rt;
                try { rt = ReqType.valueOf(type); }
                catch (Exception e) {
                    RespShell resp = new RespShell();
                    resp.requestId = requestId;
                    resp.ok = false; resp.msg = "unknown type";
                    String outJson = mapper.writeValueAsString(resp);
                    safeWrite(out, outJson);
                    System.out.println("[SEND] " + outJson);
                    continue;
                }

                RespShell resp;
                switch (rt) {
                    case SIGNUP -> {
                        String username = optText(node, "username");
                        String password = optText(node, "password");
                        resp = doSignUp(username, password);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case LOGIN -> {
                        String username = optText(node, "username");
                        String password = optText(node, "password");
                        resp = doLogin(username, password);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                        if (resp.ok && resp.playerId != null) {
                            bindConn(resp.playerId, conn);
                        }
                    }
                    case PLANT -> {
                        Integer pid = optInt(node, "playerId"); // 目前仍信任该字段
                        Integer row = optInt(node, "row");
                        Integer col = optInt(node, "col");
                        resp = doPlant(pid, row, col);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case HARVEST -> {
                        Integer pid = optInt(node, "playerId");
                        Integer row = optInt(node, "row");
                        Integer col = optInt(node, "col");
                        resp = doHarvest(pid, row, col);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case ADD_FRIEND -> { // ★ 按 ID 添加好友
                        Integer pid = optInt(node, "playerId");
                        Integer targetId = optInt(node, "targetId"); // 客户端发送自己的 playerId + targetId
                        resp = doAddFriend(pid, targetId);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case LIST_FRIENDS -> { // ★ 拉取好友列表
                        Integer pid = optInt(node, "playerId");
                        resp = doListFriends(pid);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case PING -> { /* 已在上面处理，不会到这里 */ }
                }
            }
            System.out.println("[INFO] client closed");
        } catch (EOFException | java.net.SocketTimeoutException e) {
            System.out.println("[INFO] client closed/timeout");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null && conn.playerId != null) {
                conns.remove(conn.playerId, conn);
            }
        }
    }

    private void bindConn(int playerId, ClientConn conn) {
        ClientConn old = conns.put(playerId, conn);
        conn.playerId = playerId;
        if (old != null) {
            try {
                String msg = "{\"type\":\"INFO\",\"msg\":\"relogin\"}";
                old.out.write(msg); old.out.write("\n"); old.out.flush();
                System.out.println("[SEND] " + msg);
            } catch (Exception ignore) {}
            try { old.socket.close(); } catch (Exception ignore) {}
        }
    }

    // ===== 业务实现 =====
    private RespShell doSignUp(String username, String password) {
        RespShell r = new RespShell();
        if (isBlank(username) || isBlank(password)) { r.ok=false; r.msg="bad request"; return r; }
        String key = username.toLowerCase(Locale.ROOT);

        Player created = new Player(nextId.getAndIncrement(), username, password, 100);
        Player prev = players.putIfAbsent(key, created);
        if (prev != null) { r.ok=false; r.msg="player exists"; return r; }

        playersById.put(created.getId(), created);
        farms.putIfAbsent(created.getId(), new Farm());
        // ★ 为新玩家建空好友集合
        friends.putIfAbsent(created.getId(), ConcurrentHashMap.newKeySet());

        savePlayersAsync();
        saveFarmsAsync();
        saveFriendsAsync(); // ★

        r.ok = true; r.msg="signup ok";
        return r;
    }

    private RespShell doLogin(String username, String password) {
        RespShell r = new RespShell();
        if (isBlank(username) || isBlank(password)) { r.ok=false; r.msg="bad request"; return r; }
        Player p = players.get(username.toLowerCase(Locale.ROOT));
        if (p == null) { r.ok=false; r.msg="no such player"; return r; }
        if (!p.passwordEquals(password)) { r.ok=false; r.msg="wrong password"; return r; }

        // 确保有农场
        farms.putIfAbsent(p.getId(), new Farm());
        Farm f = farms.get(p.getId());

        // 确保有好友集合
        friends.putIfAbsent(p.getId(), ConcurrentHashMap.newKeySet());

        // 生成 session（暂时只回显，不强校验）
        String session = UUID.randomUUID().toString();

        // 基本玩家信息
        r.ok = true;
        r.msg = "login ok";
        r.playerId   = p.getId();
        r.playerName = p.getName();
        r.coins      = p.getCoins();
        r.session    = session;

        // 附带当前农场快照：rows / cols / cells
        if (f != null) {
            r.rows = f.rows;
            r.cols = f.cols;
            r.cells = new ArrayList<>(f.rows * f.cols);
            for (int rr = 0; rr < f.rows; rr++) {
                for (int cc = 0; cc < f.cols; cc++) {
                    PlotState ps = f.board[rr][cc];
                    if (ps == null) ps = PlotState.EMPTY;
                    r.cells.add(ps.name());  // "EMPTY"/"GROWING"/"RIPE"
                }
            }
        }

        return r;
    }

    private RespShell doPlant(Integer playerId, Integer row, Integer col) {
        RespShell r = new RespShell();
        if (playerId==null || row==null || col==null) { r.ok=false; r.msg="bad request"; return r; }
        Player p = playersById.get(playerId);
        if (p == null) { r.ok=false; r.msg="no such player"; return r; }
        Farm f = farms.get(playerId);
        if (f == null) { r.ok=false; r.msg="no farm"; return r; }

        synchronized (f) {
            if (outOfRange(f, row, col)) { r.ok=false; r.msg="out of range"; return r; }
            if (f.board[row][col] != PlotState.EMPTY) { r.ok=false; r.msg="plot occupied"; return r; }
            if (p.getCoins() < 10) { r.ok=false; r.msg="not enough coins"; return r; }

            p.setCoins(p.getCoins() - 10);
            f.board[row][col] = PlotState.GROWING;
            long now = System.currentTimeMillis();
            long ripetime = now + 5000;
            f.ripeAt[row][col] = ripetime;

            // 5s后成熟
            final int rr=row, cc=col, pid=playerId;
            scheduler.schedule(() -> {
                Farm ff = farms.get(pid);
                Player pp = playersById.get(pid);
                if (ff == null || pp == null) return;
                synchronized (ff) {
                    if (ff.board[rr][cc] == PlotState.GROWING) {
                        ff.board[rr][cc] = PlotState.RIPE;
                        ff.ripeAt[rr][cc] = null;
                        pushTo(pid, new PushCellUpdate(pid, rr, cc, PlotState.RIPE, pp.getCoins()));
                        saveFarmsAsync();
                    }
                }
            }, ripetime - now, TimeUnit.MILLISECONDS);

            savePlayersAsync(); // 扣钱
            saveFarmsAsync();   // 改状态 + 记录 ripeAt

            r.ok = true; r.msg="plant ok";
            r.playerId = playerId; r.row=row; r.col=col;
            r.plotState=PlotState.GROWING.name(); r.coins=p.getCoins();
            return r;
        }
    }

    private RespShell doHarvest(Integer playerId, Integer row, Integer col) {
        RespShell r = new RespShell();
        if (playerId==null || row==null || col==null) { r.ok=false; r.msg="bad request"; return r; }
        Player p = playersById.get(playerId);
        if (p == null) { r.ok=false; r.msg="no such player"; return r; }
        Farm f = farms.get(playerId);
        if (f == null) { r.ok=false; r.msg="no farm"; return r; }

        synchronized (f) {
            if (outOfRange(f, row, col)) { r.ok=false; r.msg="out of range"; return r; }
            if (f.board[row][col] != PlotState.RIPE) { r.ok=false; r.msg="not ripe"; return r; }

            f.board[row][col] = PlotState.EMPTY;
            f.ripeAt[row][col] = null;
            p.setCoins(p.getCoins() + 20);

            savePlayersAsync();
            saveFarmsAsync();

            r.ok = true; r.msg="harvest ok";
            r.playerId=playerId; r.row=row; r.col=col;
            r.plotState=PlotState.EMPTY.name(); r.coins=p.getCoins();

            pushTo(playerId, new PushCellUpdate(playerId, row, col, PlotState.EMPTY, p.getCoins()));
            return r;
        }
    }

    // ★ 添加好友
    private RespShell doAddFriend(Integer playerId, Integer targetId) {
        RespShell r = new RespShell();
        if (playerId == null || targetId == null) {
            r.ok = false; r.msg = "bad request"; return r;
        }
        if (Objects.equals(playerId, targetId)) {
            r.ok = false; r.msg = "cannot add yourself"; return r;
        }
        Player self = playersById.get(playerId);
        Player target = playersById.get(targetId);
        if (self == null) { r.ok=false; r.msg="no such player"; return r; }
        if (target == null) { r.ok=false; r.msg="no such player"; return r; }

        friends.putIfAbsent(playerId,   ConcurrentHashMap.newKeySet());
        friends.putIfAbsent(targetId,   ConcurrentHashMap.newKeySet());
        Set<Integer> mySet = friends.get(playerId);
        Set<Integer> tgSet = friends.get(targetId);

        if (mySet.contains(targetId)) {
            r.ok = false; r.msg = "already friend"; return r;
        }

        mySet.add(targetId);
        tgSet.add(playerId);
        saveFriendsAsync();

        r.ok = true;
        r.msg = "friend added";
        r.playerId = playerId;
        r.friendId = targetId;
        r.friendName = target.getName();
        return r;
    }

    // ★ 列出好友
    private RespShell doListFriends(Integer playerId) {
        RespShell r = new RespShell();
        if (playerId == null) {
            r.ok = false; r.msg = "bad request"; return r;
        }
        Player self = playersById.get(playerId);
        if (self == null) { r.ok=false; r.msg="no such player"; return r; }

        friends.putIfAbsent(playerId, ConcurrentHashMap.newKeySet());
        Set<Integer> mySet = friends.get(playerId);

        List<FriendInfo> list = new ArrayList<>();
        for (Integer fid : mySet) {
            Player fp = playersById.get(fid);
            if (fp != null) {
                list.add(new FriendInfo(fid, fp.getName()));
            }
        }

        r.ok = true;
        r.msg = "friends ok";
        r.playerId = playerId;
        r.friends = list;
        return r;
    }

    // ===== 推送 =====
    private void pushTo(int playerId, Object payload) {
        ClientConn cc = conns.get(playerId);
        if (cc == null) return;
        try {
            String line = mapper.writeValueAsString(payload);
            cc.safeWrite(line);
            System.out.println("[PUSH] " + line);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    // ===== IO & 工具 =====
    private static class ClientConn {
        final Socket socket;
        final BufferedWriter out;
        final Object writeLock = new Object();
        volatile Integer playerId;

        ClientConn(Socket socket, BufferedWriter out) {
            this.socket = socket; this.out = out;
        }
        void safeWrite(String jsonLine) {
            synchronized (writeLock) {
                try { out.write(jsonLine); out.write("\n"); out.flush(); }
                catch (IOException e) { /* 写失败由上层清理 */ }
            }
        }
    }

    private static boolean outOfRange(Farm f, int r, int c) {
        return r < 0 || r >= f.rows || c < 0 || c >= f.cols;
    }
    static boolean isBlank(String s){ return s == null || s.trim().isEmpty(); }
    private static String optText(JsonNode n, String k){ JsonNode v=n.get(k); return v!=null && !v.isNull()? v.asText() : null; }
    private static Integer optInt(JsonNode n, String k){ JsonNode v=n.get(k); return (v!=null && v.isInt())? v.asInt() : (v!=null && v.isNumber()? v.numberValue().intValue(): null); }
    private static void safeWrite(Writer out, String s) throws IOException { out.write(s); out.write("\n"); out.flush(); }

    // ===== players.json 持久化 =====
    public static class PersistPlayer {
        public int id; public String name; public String password; public int coins;
        public PersistPlayer() {}
        public PersistPlayer(int id, String name, String password, int coins) {
            this.id=id; this.name=name; this.password=password; this.coins=coins;
        }
        public static PersistPlayer from(Player p) { return new PersistPlayer(p.getId(), p.getName(), p.getPassword(), p.getCoins()); }
    }

    private void loadPlayersFromDisk() {
        try {
            if (!Files.exists(PLAYERS_FILE)) {
                Files.createDirectories(DATA_DIR);
                System.out.println("[LOAD] no players.json, start fresh.");
                return;
            }
            byte[] bytes = Files.readAllBytes(PLAYERS_FILE);
            if (bytes.length == 0) return;

            List<PersistPlayer> list = mapper.readValue(bytes, new TypeReference<List<PersistPlayer>>() {});
            int maxId = 0;
            for (PersistPlayer pp : list) {
                Player p = new Player(pp.id, pp.name, pp.password, pp.coins);
                players.put(pp.name.toLowerCase(Locale.ROOT), p);
                playersById.put(p.getId(), p);
                if (pp.id > maxId) maxId = pp.id;
            }
            nextId.set(maxId + 1);
            System.out.println("[LOAD] players=" + players.size() + ", nextId=" + nextId.get());
        } catch (Exception e) {
            System.err.println("[LOAD] players failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void savePlayersAsync() {
        diskWriter.submit(this::flushPlayersSync);
    }

    private void flushPlayersSync() {
        try {
            Files.createDirectories(DATA_DIR);

            List<PersistPlayer> list = new ArrayList<>(players.size());
            for (Player p : players.values()) list.add(PersistPlayer.from(p));

            Path tmp = PLAYERS_FILE.resolveSibling(PLAYERS_FILE.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, list);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) { ch.force(true); }
            Files.move(tmp, PLAYERS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel dir = FileChannel.open(DATA_DIR, StandardOpenOption.READ)) { dir.force(true); }

            System.out.println("[SAVE] players=" + list.size() + " -> " + PLAYERS_FILE);
        } catch (Exception e) {
            System.err.println("[SAVE] players failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== farms.json 持久化 =====
    public static class PersistCell {
        public String state;     // "EMPTY"/"GROWING"/"RIPE"
        public Long ripeAt;      // 仅当 GROWING 时非空
        public PersistCell() {}
        public PersistCell(String state, Long ripeAt){ this.state=state; this.ripeAt=ripeAt; }
    }

    public static class PersistFarm {
        public int playerId;
        public int rows;
        public int cols;
        public List<PersistCell> cells;
        public PersistFarm() {}
    }

    private PersistFarm toPersistFarm(int playerId, Farm f) {
        PersistFarm pf = new PersistFarm();
        pf.playerId = playerId; pf.rows = f.rows; pf.cols = f.cols;
        pf.cells = new ArrayList<>(f.rows * f.cols);
        for (int r=0; r<f.rows; r++) {
            for (int c=0; c<f.cols; c++) {
                PlotState s = f.board[r][c];
                Long ra = f.ripeAt[r][c];
                pf.cells.add(new PersistCell(s.name(), ra));
            }
        }
        return pf;
    }

    private Farm fromPersistFarm(PersistFarm pf) {
        Farm f = new Farm();
        if (pf == null) return f;
        long now = System.currentTimeMillis();
        int idx = 0;
        for (int r=0; r<f.rows; r++) {
            for (int c=0; c<f.cols; c++) {
                PersistCell pc = (pf.cells != null && idx < pf.cells.size()) ? pf.cells.get(idx) : null;
                idx++;
                if (pc == null || pc.state == null) {
                    f.board[r][c] = PlotState.EMPTY; f.ripeAt[r][c] = null; continue;
                }
                PlotState s = PlotState.valueOf(pc.state);
                if (s == PlotState.GROWING && pc.ripeAt != null) {
                    if (now >= pc.ripeAt) {
                        f.board[r][c] = PlotState.RIPE; f.ripeAt[r][c] = null;
                    } else {
                        f.board[r][c] = PlotState.GROWING; f.ripeAt[r][c] = pc.ripeAt;
                        final int rr=r, cc=c, pid=pf.playerId;
                        long delay = pc.ripeAt - now;
                        scheduler.schedule(() -> {
                            Farm ff = farms.get(pid);
                            Player pp = playersById.get(pid);
                            if (ff == null || pp == null) return;
                            synchronized (ff) {
                                if (ff.board[rr][cc] == PlotState.GROWING) {
                                    ff.board[rr][cc] = PlotState.RIPE;
                                    ff.ripeAt[rr][cc] = null;
                                    pushTo(pid, new PushCellUpdate(pid, rr, cc, PlotState.RIPE, pp.getCoins()));
                                    saveFarmsAsync();
                                }
                            }
                        }, delay, TimeUnit.MILLISECONDS);
                    }
                } else {
                    f.board[r][c] = s;
                    f.ripeAt[r][c] = null;
                }
            }
        }
        return f;
    }

    private void loadFarmsFromDisk() {
        try {
            if (!Files.exists(FARMS_FILE)) {
                // 没有 farms.json：为已有玩家建空农场
                for (Player p : playersById.values()) {
                    farms.putIfAbsent(p.getId(), new Farm());
                }
                System.out.println("[LOAD] no farms.json, create empty farms for players=" + playersById.size());
                return;
            }
            byte[] bytes = Files.readAllBytes(FARMS_FILE);
            if (bytes.length == 0) return;

            List<PersistFarm> list = mapper.readValue(bytes, new TypeReference<List<PersistFarm>>() {});
            int count = 0;
            for (PersistFarm pf : list) {
                Farm f = fromPersistFarm(pf);
                farms.put(pf.playerId, f);
                count++;
            }
            // 确保每个已知玩家都有农场
            for (Player p : playersById.values()) {
                farms.putIfAbsent(p.getId(), new Farm());
            }
            System.out.println("[LOAD] farms=" + count + ", playersWithFarm=" + farms.size());
        } catch (Exception e) {
            System.err.println("[LOAD] farms failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void saveFarmsAsync() {
        diskWriter.submit(this::flushFarmsSync);
    }

    private void flushFarmsSync() {
        try {
            Files.createDirectories(DATA_DIR);

            List<PersistFarm> list = new ArrayList<>(farms.size());
            for (var e : farms.entrySet()) {
                int pid = e.getKey();
                Farm f = e.getValue();
                synchronized (f) { // 避免与游戏操作竞态
                    list.add(toPersistFarm(pid, f));
                }
            }

            Path tmp = FARMS_FILE.resolveSibling(FARMS_FILE.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, list);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) { ch.force(true); }
            Files.move(tmp, FARMS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel dir = FileChannel.open(DATA_DIR, StandardOpenOption.READ)) { dir.force(true); }

            System.out.println("[SAVE] farms=" + list.size() + " -> " + FARMS_FILE);
        } catch (Exception e) {
            System.err.println("[SAVE] farms failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== friends.json 持久化 =====
    public static class PersistFriends {
        public int playerId;
        public List<Integer> friends;
        public PersistFriends() {}
        public PersistFriends(int playerId, List<Integer> friends) {
            this.playerId = playerId; this.friends = friends;
        }
    }

    private void loadFriendsFromDisk() {
        try {
            if (!Files.exists(FRIENDS_FILE)) {
                // 没有 friends.json：为已有玩家建空好友集合
                for (Player p : playersById.values()) {
                    friends.putIfAbsent(p.getId(), ConcurrentHashMap.newKeySet());
                }
                System.out.println("[LOAD] no friends.json, start with empty friend sets for players=" + playersById.size());
                return;
            }
            byte[] bytes = Files.readAllBytes(FRIENDS_FILE);
            if (bytes.length == 0) return;

            List<PersistFriends> list = mapper.readValue(bytes, new TypeReference<List<PersistFriends>>() {});
            int count = 0;
            for (PersistFriends pf : list) {
                Set<Integer> set = ConcurrentHashMap.newKeySet();
                if (pf.friends != null) set.addAll(pf.friends);
                friends.put(pf.playerId, set);
                count++;
            }
            // 确保每个已有玩家都有记录
            for (Player p : playersById.values()) {
                friends.putIfAbsent(p.getId(), ConcurrentHashMap.newKeySet());
            }
            System.out.println("[LOAD] friends entries=" + count + ", playersWithFriends=" + friends.size());
        } catch (Exception e) {
            System.err.println("[LOAD] friends failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void saveFriendsAsync() {
        diskWriter.submit(this::flushFriendsSync);
    }

    private void flushFriendsSync() {
        try {
            Files.createDirectories(DATA_DIR);

            List<PersistFriends> list = new ArrayList<>(friends.size());
            for (var e : friends.entrySet()) {
                int pid = e.getKey();
                Set<Integer> set = e.getValue();
                list.add(new PersistFriends(pid, new ArrayList<>(set)));
            }

            Path tmp = FRIENDS_FILE.resolveSibling(FRIENDS_FILE.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, list);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) { ch.force(true); }
            Files.move(tmp, FRIENDS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel dir = FileChannel.open(DATA_DIR, StandardOpenOption.READ)) { dir.force(true); }

            System.out.println("[SAVE] friends=" + list.size() + " -> " + FRIENDS_FILE);
        } catch (Exception e) {
            System.err.println("[SAVE] friends failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== main =====
    public static void main(String[] args) throws Exception {
        new Server(5555).start();
    }
}
