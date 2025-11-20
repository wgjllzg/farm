module org.example.demo {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.logging;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;

    opens org.example.demo to javafx.fxml, com.fasterxml.jackson.databind;

    exports org.example.demo;
}
