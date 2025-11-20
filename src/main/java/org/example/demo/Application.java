package org.example.demo;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;

/**
 * Entry point for the simplified QQ Farm demo.
 */
public class Application extends javafx.application.Application {

    @Override
    public void start(Stage stage) throws Exception {
        final String HOST = "127.0.0.1";
        final int PORT = 5555;

        // 加载 JavaFX 界面
        FXMLLoader loader = new FXMLLoader(Application.class.getResource("board.fxml"));
        Parent root = loader.load();
        Controller controller = loader.getController();

        // 建立和服务器的长连接（后面登录/种菜/收获都复用这一条）
        try {
            controller.connect(HOST, PORT);
        } catch (IOException e) {
            System.out.println("Cannot connect to server: " + e.getMessage());
            return;
        }

        Player player = new Player();
        String session = "";

        // 用来保存服务器返回的农场快照
        int snapshotRows = 4;
        int snapshotCols = 4;
        String[] snapshotCells = new String[0];
        int snapshotCoins = 0;

        // === 简易控制台登录/注册 ===
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.println("\n=== QQ Farm Login ===");
            System.out.println("Login: 1, SignUp: 2, Exit: 0");
            System.out.print("Select> ");
            String choice = sc.nextLine().trim();

            boolean done = false;
            switch (choice) {
                case "1" -> { // 登录
                    String username = prompt(sc, "Username: ");
                    String password = prompt(sc, "Password:  ");

                    try {
                        // 通过 Controller 的长连接调用 LOGIN
                        JsonNode res = controller.login(username, password).get();
                        boolean ok = res.path("ok").asBoolean(false);
                        if (ok) {
                            int pid = res.path("playerId").asInt();
                            String pname = res.path("playerName").asText(username);
                            int coins = res.path("coins").asInt(0);
                            session = res.path("session").asText("");

                            player.setId(pid);
                            player.setName(pname);
                            player.setCoins(coins);

                            // 读取服务器返回的农场快照 rows / cols / cells
                            snapshotRows = res.path("rows").asInt(4);
                            snapshotCols = res.path("cols").asInt(4);
                            JsonNode cellsNode = res.path("cells");
                            if (cellsNode != null && cellsNode.isArray()) {
                                snapshotCells = new String[cellsNode.size()];
                                for (int i = 0; i < cellsNode.size(); i++) {
                                    snapshotCells[i] = cellsNode.get(i).asText("EMPTY");
                                }
                            } else {
                                snapshotCells = new String[0];
                            }
                            snapshotCoins = coins;

                            System.out.println("Login OK. " +
                                    "playerName=" + pname +
                                    ", playerId=" + pid +
                                    ", coins=" + coins);
                            done = true;
                        } else {
                            String msg = res.path("msg").asText("login failed");
                            System.out.println("Login failed: " + msg);
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        System.out.println("Network/login error: " + e.getMessage());
                    }
                }
                case "2" -> { // 注册
                    String username = prompt(sc, "New username: ");
                    String pass1 = prompt(sc, "New password (≥6 chars): ");
                    String pass2 = prompt(sc, "Confirm password:      ");
                    if (!pass1.equals(pass2)) {
                        System.out.println("Passwords do not match.");
                        break;
                    }
                    if (pass1.length() < 6) {
                        System.out.println("Password too short.");
                        break;
                    }
                    try {
                        JsonNode res = controller.signup(username, pass1).get();
                        boolean ok = res.path("ok").asBoolean(false);
                        if (ok) {
                            System.out.println("SignUp success. You can now log in.");
                        } else {
                            String msg = res.path("msg").asText("signup failed");
                            System.out.println("SignUp failed: " + msg);
                        }
                    } catch (InterruptedException | ExecutionException e) {
                        System.out.println("Network/signup error: " + e.getMessage());
                    }
                }
                case "0" -> {
                    System.out.println("Bye.");
                    return;
                }
                default -> System.out.println("Invalid option, try again.");
            }
            if (done) break; // 登录成功，退出循环
        }

        // === 进入游戏 ===
        Game game = new Game(player);

        // 初始化 Controller（带上 session，后续 PLANT/HARVEST 请求会用到）
        controller.init(game, session);

        // ★ 关键：用登录响应中的农场快照恢复棋盘 + 硬币
        controller.applySnapshotFromServer(snapshotRows, snapshotCols, snapshotCells, snapshotCoins);
        controller.loadFriendsFromServer();

        Scene scene = new Scene(root);
        stage.setTitle("QQ Farm");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> controller.shutdown());
        stage.show();
    }

    private static String prompt(Scanner sc, String text) {
        System.out.print(text);
        return sc.nextLine().trim();
    }

    public static void main(String[] args) {
        launch();
    }
}
