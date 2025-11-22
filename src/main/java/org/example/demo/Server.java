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
    public enum ReqType {
        LOGIN, SIGNUP, PLANT, HARVEST, PING,
        ADD_FRIEND, LIST_FRIENDS, VISIT_FARM, STEAL
    }
    public enum PlotState { EMPTY, GROWING, RIPE }

    /** 统一响应外壳（同一条长连上的 request/response） */
    static class RespShell {
        public String type = "RESP";
        public String requestId;
        public boolean ok;
        public String msg;

        // 通用字段
        public Integer playerId;     // 发起请求的人
        public Integer coins;        // 一般返回“自己的金币”（发起者）
        public String session;
        public String playerName;

        // 农场单格操作
        public Integer row, col;
        public String plotState;

        // 农场快照
        public Integer rows, cols;
        public List<String> cells;

        // 好友相关
        public Integer friendId;
        public String friendName;
        public List<FriendInfo> friends;

        // 访问农场相关
        public Integer targetId;
        public String targetName;
        public Boolean ownerOnline;     // 农场主是否在线
        public Boolean canSteal;        // 这一轮还有没有偷菜额度

        // 偷菜响应可选字段（农场主剩余金币）
        public Integer ownerCoins;
    }

    /** 主动推送：单格更新（成熟/收获/播种/被偷） */
    static class PushCellUpdate {
        public String type = "PUSH_CELL_UPDATE";
        public Integer playerId;     // 农场主人 id
        public Integer row, col;
        public String plotState;     // EMPTY/GROWING/RIPE
        public Integer coins;        // 农场主自己的金币

        public PushCellUpdate() {}
        public PushCellUpdate(int ownerId, int r, int c, PlotState ps, int coins){
            this.playerId = ownerId;
            this.row = r; this.col = c;
            this.plotState = ps.name();
            this.coins = coins;
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

    /** 好友关系：playerId -> 好友 id 集合（对称） */
    private final Map<Integer, Set<Integer>> friends = new ConcurrentHashMap<>();

    /** 观众关系：ownerId -> 当前正在看这个人农场的 viewerId 集合（不含本人） */
    private final Map<Integer, Set<Integer>> viewersByOwner = new ConcurrentHashMap<>();
    /** viewerId -> 当前正在看的 ownerId（可以是自己或别人） */
    private final Map<Integer, Integer> currentViewByViewer = new ConcurrentHashMap<>();

    /** 偷菜配额状态（每个 ownerId） */
    private final Map<Integer, Integer> baselineRipe = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> allowedSteals = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> stolenSoFar   = new ConcurrentHashMap<>();

    private final AtomicInteger nextId = new AtomicInteger(1);

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

    // 持久化文件
    private static final Path DATA_DIR     = Paths.get("data");
    private static final Path PLAYERS_FILE = DATA_DIR.resolve("players.json");
    private static final Path FARMS_FILE   = DATA_DIR.resolve("farms.json");
    private static final Path FRIENDS_FILE = DATA_DIR.resolve("friends.json");
    private final ExecutorService diskWriter = Executors.newSingleThreadExecutor();

    public Server(int port) { this.port = port; }

    // ===== 启动 =====
    public void start() throws IOException {
        loadPlayersFromDisk();
        loadFarmsFromDisk();
        loadFriendsFromDisk();
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

                System.out.println("[RECV] " + line);

                JsonNode node = mapper.readTree(line);
                String requestId = optText(node, "requestId");
                String type = optText(node, "type");
                if (type == null) {
                    RespShell bad = new RespShell();
                    bad.requestId = requestId;
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
                        Integer pid = optInt(node, "playerId");
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
                    case ADD_FRIEND -> {
                        Integer pid = optInt(node, "playerId");
                        Integer targetId = optInt(node, "targetId");
                        resp = doAddFriend(pid, targetId);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case LIST_FRIENDS -> {
                        Integer pid = optInt(node, "playerId");
                        resp = doListFriends(pid);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case VISIT_FARM -> {
                        Integer pid = optInt(node, "playerId");
                        Integer targetId = optInt(node, "targetId");
                        resp = doVisitFarm(pid, targetId);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                    case STEAL -> {
                        Integer pid = optInt(node, "playerId");
                        Integer targetId = optInt(node, "targetId");
                        Integer row = optInt(node, "row");
                        Integer col = optInt(node, "col");
                        resp = doSteal(pid, targetId, row, col);
                        resp.requestId = requestId;
                        String outJson = mapper.writeValueAsString(resp);
                        safeWrite(out, outJson);
                        System.out.println("[SEND] " + outJson);
                    }
                }
            }
            System.out.println("[INFO] client closed");
        } catch (EOFException | java.net.SocketTimeoutException e) {
            System.out.println("[INFO] client closed/timeout");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null && conn.playerId != null) {
                int viewerId = conn.playerId;
                conns.remove(viewerId, conn);

                // 清理观众关系
                Integer owner = currentViewByViewer.remove(viewerId);
                if (owner != null) {
                    Set<Integer> vs = viewersByOwner.get(owner);
                    if (vs != null) {
                        vs.remove(viewerId);
                        if (vs.isEmpty()) viewersByOwner.remove(owner);
                    }
                }
            }
        }
    }

    private void bindConn(int playerId, ClientConn conn) {
        // 主人重新上线：重置偷菜状态
        resetStealState(playerId);

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

        Player created = new Player();
        created.setId(nextId.getAndIncrement());
        created.setName(username);
        created.setCoins(100);
        try {
            var pwdField = Player.class.getDeclaredField("password");
            pwdField.setAccessible(true);
            pwdField.set(created, password);
        } catch (Exception ignore) {}

        Player prev = players.putIfAbsent(key, created);
        if (prev != null) { r.ok=false; r.msg="player exists"; return r; }

        playersById.put(created.getId(), created);
        farms.putIfAbsent(created.getId(), new Farm());
        friends.putIfAbsent(created.getId(), ConcurrentHashMap.newKeySet());

        savePlayersAsync();
        saveFarmsAsync();
        saveFriendsAsync();

        r.ok = true; r.msg="signup ok";
        return r;
    }

    private RespShell doLogin(String username, String password) {
        RespShell r = new RespShell();
        if (isBlank(username) || isBlank(password)) { r.ok=false; r.msg="bad request"; return r; }
        Player p = players.get(username.toLowerCase(Locale.ROOT));
        if (p == null) { r.ok=false; r.msg="no such player"; return r; }
        if (!p.passwordEquals(password)) { r.ok=false; r.msg="wrong password"; return r; }
        farms.putIfAbsent(p.getId(), new Farm());
        friends.putIfAbsent(p.getId(), ConcurrentHashMap.newKeySet());

        String session = UUID.randomUUID().toString(); // 先占位（未校验）
        r.ok = true; r.msg="login ok";
        r.playerId = p.getId();
        r.playerName = p.getName();
        r.coins = p.getCoins();
        r.session = session;

        // 附带自己的农场快照
        Farm f = farms.get(p.getId());
        r.rows = f.rows;
        r.cols = f.cols;
        r.cells = farmToCells(f);

        return r;
    }

    /** PLANT：广播 GROWING 给所有正在看该农场的人 */
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

            final int rr=row, cc=col, pid=playerId;
            scheduler.schedule(() -> {
                Farm ff = farms.get(pid);
                Player pp = playersById.get(pid);
                if (ff == null || pp == null) return;
                synchronized (ff) {
                    if (ff.board[rr][cc] == PlotState.GROWING) {
                        ff.board[rr][cc] = PlotState.RIPE;
                        ff.ripeAt[rr][cc] = null;
                        broadcastFarmUpdate(pid, new PushCellUpdate(pid, rr, cc, PlotState.RIPE, pp.getCoins()));
                        saveFarmsAsync();
                    }
                }
            }, ripetime - now, TimeUnit.MILLISECONDS);

            savePlayersAsync();
            saveFarmsAsync();

            // 立刻广播 GROWING
            broadcastFarmUpdate(playerId,
                    new PushCellUpdate(playerId, row, col, PlotState.GROWING, p.getCoins()));

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

            // 如果没有任何 RIPE 了，重置偷菜配额
            if (countRipe(f) == 0) {
                resetStealState(playerId);
            }

            savePlayersAsync();
            saveFarmsAsync();

            r.ok = true; r.msg="harvest ok";
            r.playerId=playerId; r.row=row; r.col=col;
            r.plotState=PlotState.EMPTY.name(); r.coins=p.getCoins();

            broadcastFarmUpdate(playerId,
                    new PushCellUpdate(playerId, row, col, PlotState.EMPTY, p.getCoins()));
            return r;
        }
    }

    // ===== 好友逻辑 =====
    public static class FriendInfo {
        public int id;
        public String name;
        public FriendInfo() {}
        public FriendInfo(int id, String name) { this.id = id; this.name = name; }
    }

    private RespShell doAddFriend(Integer playerId, Integer targetId) {
        RespShell r = new RespShell();
        if (playerId == null || targetId == null) { r.ok=false; r.msg="bad request"; return r; }
        if (Objects.equals(playerId, targetId)) {
            r.ok=false; r.msg="cannot add yourself"; return r;
        }
        Player me = playersById.get(playerId);
        Player other = playersById.get(targetId);
        if (me == null || other == null) {
            r.ok=false; r.msg="no such player"; return r;
        }
        friends.putIfAbsent(playerId, ConcurrentHashMap.newKeySet());
        friends.putIfAbsent(targetId, ConcurrentHashMap.newKeySet());
        Set<Integer> mySet = friends.get(playerId);
        Set<Integer> hisSet = friends.get(targetId);

        if (mySet.contains(targetId)) {
            r.ok=false; r.msg="already friends"; return r;
        }

        mySet.add(targetId);
        hisSet.add(playerId);
        saveFriendsAsync();

        r.ok = true; r.msg="add friend ok";
        r.playerId = playerId;
        r.friendId = targetId;
        r.friendName = other.getName();
        return r;
    }

    private RespShell doListFriends(Integer playerId) {
        RespShell r = new RespShell();
        if (playerId == null) { r.ok=false; r.msg="bad request"; return r; }
        Player me = playersById.get(playerId);
        if (me == null) { r.ok=false; r.msg="no such player"; return r; }

        Set<Integer> ids = friends.getOrDefault(playerId, Collections.emptySet());
        List<FriendInfo> list = new ArrayList<>();
        for (Integer fid : ids) {
            Player p = playersById.get(fid);
            if (p != null) {
                list.add(new FriendInfo(p.getId(), p.getName()));
            }
        }
        r.ok = true; r.msg = "list friends ok";
        r.playerId = playerId;
        r.friends = list;
        return r;
    }

    // ===== 访问农场逻辑 =====
    private RespShell doVisitFarm(Integer playerId, Integer targetId) {
        RespShell r = new RespShell();
        if (playerId == null || targetId == null) { r.ok=false; r.msg="bad request"; return r; }
        Player viewer = playersById.get(playerId);
        Player owner  = playersById.get(targetId);
        if (viewer == null || owner == null) { r.ok=false; r.msg="no such player"; return r; }

        // 必须是自己或者好友
        if (!Objects.equals(playerId, targetId)) {
            Set<Integer> myFriends = friends.getOrDefault(playerId, Collections.emptySet());
            if (!myFriends.contains(targetId)) {
                r.ok=false; r.msg="not friends"; return r;
            }
        }

        farms.putIfAbsent(targetId, new Farm());
        Farm f = farms.get(targetId);

        r.ok = true; r.msg = "visit ok";
        r.playerId = playerId;
        r.targetId = targetId;
        r.targetName = owner.getName();

        boolean online = conns.containsKey(targetId);
        r.ownerOnline = online;
        // 有偷菜额度：必须离线且 hasStealQuota 返回 true
        r.canSteal = !online && hasStealQuota(targetId);

        if (Objects.equals(playerId, targetId)) {
            r.coins = viewer.getCoins(); // 回到自己农场时返回自己的金币
        }
        r.rows = f.rows;
        r.cols = f.cols;
        r.cells = farmToCells(f);

        // 更新“谁在看谁”
        Integer oldOwner = currentViewByViewer.put(playerId, targetId);
        if (oldOwner != null && !oldOwner.equals(targetId)) {
            Set<Integer> vs = viewersByOwner.get(oldOwner);
            if (vs != null) {
                vs.remove(playerId);
                if (vs.isEmpty()) viewersByOwner.remove(oldOwner);
            }
        }
        if (!Objects.equals(playerId, targetId)) {
            viewersByOwner
                    .computeIfAbsent(targetId, k -> ConcurrentHashMap.newKeySet())
                    .add(playerId);
        }
        return r;
    }

    private List<String> farmToCells(Farm f) {
        List<String> list = new ArrayList<>(f.rows * f.cols);
        for (int r=0;r<f.rows;r++) {
            for (int c=0;c<f.cols;c++) {
                list.add(f.board[r][c].name());
            }
        }
        return list;
    }

    // ===== 偷菜逻辑（<4 不能偷 + 上线重置配额） =====
    private RespShell doSteal(Integer thiefId, Integer ownerId, Integer row, Integer col) {
        RespShell r = new RespShell();
        if (thiefId == null || ownerId == null || row == null || col == null) {
            r.ok = false; r.msg = "bad request"; return r;
        }
        if (Objects.equals(thiefId, ownerId)) {
            r.ok = false; r.msg = "cannot steal from yourself"; return r;
        }

        Player thief = playersById.get(thiefId);
        Player owner = playersById.get(ownerId);
        if (thief == null || owner == null) {
            r.ok = false; r.msg = "no such player"; return r;
        }

        // 必须是好友
        Set<Integer> myFriends = friends.getOrDefault(thiefId, Collections.emptySet());
        if (!myFriends.contains(ownerId)) {
            r.ok = false; r.msg = "not friends"; return r;
        }

        // 农场主必须离线
        if (conns.containsKey(ownerId)) {
            r.ok = false; r.msg = "owner online, cannot steal"; return r;
        }

        farms.putIfAbsent(ownerId, new Farm());
        Farm f = farms.get(ownerId);

        synchronized (f) {
            if (outOfRange(f, row, col)) {
                r.ok = false; r.msg = "out of range"; return r;
            }

            int ripeCount = countRipe(f);
            if (ripeCount == 0) {
                // 没有成熟的地块，重置偷菜状态
                resetStealState(ownerId);
                r.ok = false; r.msg = "no ripe plots to steal"; return r;
            }

            // 新规则：成熟地块 < 4 时，整块农场不能被偷
            if (ripeCount < 4) {
                resetStealState(ownerId); // 保守起见清空一下状态
                r.ok = false; r.msg = "not enough ripe plots to steal (need at least 4)"; return r;
            }

            Integer baseline = baselineRipe.get(ownerId);
            Integer allowed  = allowedSteals.get(ownerId);
            Integer stolen   = stolenSoFar.get(ownerId);

            // 这一轮第一次偷：以当前 ripeCount 作为基准
            if (baseline == null || allowed == null || stolen == null) {
                baseline = ripeCount;
                // allowed = floor(baseline * 25%)，baseline>=4 时至少 1
                allowed = baseline / 4;
                if (allowed <= 0) {
                    // 理论上不会触发（前面已经保证 ripeCount>=4），防御一下
                    r.ok = false; r.msg = "not enough ripe plots to steal"; return r;
                }
                stolen = 0;
                baselineRipe.put(ownerId, baseline);
                allowedSteals.put(ownerId, allowed);
                stolenSoFar.put(ownerId, stolen);
            }

            if (stolen >= allowed) {
                r.ok = false; r.msg = "farm already stolen up to 25%"; return r;
            }

            if (f.board[row][col] != PlotState.RIPE) {
                r.ok = false; r.msg = "this plot is not ripe"; return r;
            }

            // 真正偷：把该格子从 RIPE -> EMPTY
            f.board[row][col] = PlotState.EMPTY;
            f.ripeAt[row][col] = null;

            // 简单设定：偷一块地就获得 20 金币，对方损失 20 金币
            thief.setCoins(thief.getCoins() + 20);
            owner.setCoins(Math.max(0, owner.getCoins() - 20));

            stolen = stolen + 1;
            stolenSoFar.put(ownerId, stolen);

            // 如果这个农场再也没有 RIPE 了，认为这一轮结束，重置偷菜状态
            if (countRipe(f) == 0) {
                resetStealState(ownerId);
            }

            savePlayersAsync();
            saveFarmsAsync();

            // 响应：返回盗贼自己的金币
            r.ok = true; r.msg = "steal ok";
            r.playerId = thiefId;
            r.targetId = ownerId;
            r.row = row; r.col = col;
            r.plotState = PlotState.EMPTY.name();
            r.coins = thief.getCoins();
            r.ownerCoins = owner.getCoins();

            // 偷完之后这一轮是否还可继续偷
            Integer a2 = allowedSteals.get(ownerId);
            Integer s2 = stolenSoFar.get(ownerId);
            boolean canStealMore = false;
            if (a2 != null && s2 != null) {
                canStealMore = s2 < a2;
            }
            r.canSteal = canStealMore;

            // 广播这块地变 EMPTY 给所有正在看该农场的人
            broadcastFarmUpdate(ownerId,
                    new PushCellUpdate(ownerId, row, col, PlotState.EMPTY, owner.getCoins()));

            return r;
        }
    }

    private int countRipe(Farm f) {
        int cnt = 0;
        for (int r=0;r<f.rows;r++) {
            for (int c=0;c<f.cols;c++) {
                if (f.board[r][c] == PlotState.RIPE) cnt++;
            }
        }
        return cnt;
    }

    private void resetStealState(int ownerId) {
        baselineRipe.remove(ownerId);
        allowedSteals.remove(ownerId);
        stolenSoFar.remove(ownerId);
    }

    /** 是否还有偷菜额度（仅用来给 VISIT_FARM 返回 canSteal，用不到 baseline 的初始化） */
    private boolean hasStealQuota(int ownerId) {
        Farm f = farms.get(ownerId);
        if (f == null) return false;
        synchronized (f) {
            int ripe = countRipe(f);
            if (ripe < 4) {
                // 成熟数 < 4，一律不能偷，同时重置状态
                resetStealState(ownerId);
                return false;
            }
            Integer baseline = baselineRipe.get(ownerId);
            Integer allowed  = allowedSteals.get(ownerId);
            Integer stolen   = stolenSoFar.get(ownerId);
            if (baseline == null || allowed == null || stolen == null) {
                // 这一轮还没开始偷，但 ripe>=4，可以开始偷
                return true;
            }
            return stolen < allowed;
        }
    }

    // ===== 推送 & 广播 =====
    private void pushTo(int playerId, Object payload) {
        ClientConn cc = conns.get(playerId);
        if (cc == null) return;
        try {
            String line = mapper.writeValueAsString(payload);
            cc.safeWrite(line);
            System.out.println("[PUSH] to " + playerId + " " + line);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    /** 广播某个农场的单格更新：推给 owner + 所有正在看他农场的观众 */
    private void broadcastFarmUpdate(int ownerId, Object payload) {
        pushTo(ownerId, payload);
        Set<Integer> vs = viewersByOwner.get(ownerId);
        if (vs != null) {
            for (Integer vid : vs) {
                if (vid == null || vid == ownerId) continue;
                pushTo(vid, payload);
            }
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
                Player p = new Player();
                p.setId(pp.id);
                p.setName(pp.name);
                p.setCoins(pp.coins);
                try {
                    var pwdField = Player.class.getDeclaredField("password");
                    pwdField.setAccessible(true);
                    pwdField.set(p, pp.password);
                } catch (Exception ignore) {}

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
                                    broadcastFarmUpdate(pid,
                                            new PushCellUpdate(pid, rr, cc, PlotState.RIPE, pp.getCoins()));
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
                synchronized (f) {
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
    public static class PersistFriendList {
        public int playerId;
        public List<Integer> friends;
        public PersistFriendList() {}
        public PersistFriendList(int playerId, List<Integer> friends) {
            this.playerId = playerId;
            this.friends = friends;
        }
    }

    private void loadFriendsFromDisk() {
        try {
            if (!Files.exists(FRIENDS_FILE)) {
                System.out.println("[LOAD] no friends.json, start empty friends.");
                return;
            }
            byte[] bytes = Files.readAllBytes(FRIENDS_FILE);
            if (bytes.length == 0) return;

            List<PersistFriendList> list = mapper.readValue(bytes, new TypeReference<List<PersistFriendList>>() {});
            for (PersistFriendList pfl : list) {
                Set<Integer> set = ConcurrentHashMap.newKeySet();
                if (pfl.friends != null) set.addAll(pfl.friends);
                friends.put(pfl.playerId, set);
            }
            System.out.println("[LOAD] friends for players=" + friends.size());
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

            List<PersistFriendList> list = new ArrayList<>();
            for (var e : friends.entrySet()) {
                int pid = e.getKey();
                Set<Integer> set = e.getValue();
                list.add(new PersistFriendList(pid, new ArrayList<>(set)));
            }

            Path tmp = FRIENDS_FILE.resolveSibling(FRIENDS_FILE.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, list);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.READ)) { ch.force(true); }
            Files.move(tmp, FRIENDS_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel dir = FileChannel.open(DATA_DIR, StandardOpenOption.READ)) { dir.force(true); }

            System.out.println("[SAVE] friends for players=" + list.size() + " -> " + FRIENDS_FILE);
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
