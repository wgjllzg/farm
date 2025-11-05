package org.example.demo;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Random;

/**
 * 客户端入口：启动 JavaFX，并与服务器建立连接。
 */
public class Application extends javafx.application.Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(Application.class.getResource("board.fxml"));
        Parent root = loader.load();

        Controller controller = loader.getController();

        // 简单起名：Player-随机数。需要的话你可以改成弹框输入用户名。
        String playerName = "Player-" + new Random().nextInt(1000);

        // 连接本机 5555 端口的服务器（先启动 FarmServer 再启动客户端）
        controller.init("localhost", 5555, playerName);

        Scene scene = new Scene(root);
        stage.setTitle("QQ Farm Demo - " + playerName);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> controller.shutdown());
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
