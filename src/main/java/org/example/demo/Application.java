package org.example.demo;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Entry point for the simplified QQ Farm demo.
 */
public class Application extends javafx.application.Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(Application.class.getResource("board.fxml"));
        Parent root = loader.load();

        Controller controller = loader.getController();
        controller.init(new Game());
        // TODO: 在此处建立与服务器的真实连接，并把共享的 Game 状态换成网络同步模型。

        Scene scene = new Scene(root);
        stage.setTitle("QQ Farm Demo");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> controller.shutdown());
        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }
}
