package org.example.demo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class LoginClient {

    private final String host;
    private final int port;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LoginClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public LoginResult login(String username, String password) throws IOException {
        return call(new Req(ReqType.LOGIN, username, password));
    }

    public LoginResult signup(String username, String password) throws IOException {
        return call(new Req(ReqType.SIGNUP, username, password));
    }

    private LoginResult call(Req req) throws IOException {
        try (Socket s = new Socket(host, port)) {
            if (s == null) throw new IOException("LoginClient failed to connect server.");
            s.setSoTimeout(8000);

            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {

                out.write(mapper.writeValueAsString(req));
                out.write("\n");
                out.flush();

                String line = in.readLine();
                if (line == null) throw new EOFException("server closed");

                Resp resp = mapper.readValue(line, Resp.class);
                return new LoginResult(resp.ok, resp.msg, resp.playerId, resp.coins, resp.session, resp.playerName);
            }
        }
    }

    public enum ReqType { LOGIN, SIGNUP }

    public static class Req {
        public ReqType type;
        public String username;
        public String password;
        public Req() {}
        public Req(ReqType type, String username, String password) {
            this.type = type; this.username = username; this.password = password;
        }
    }

    public static class Resp {
        public boolean ok;
        public String msg;
        public Integer playerId;
        public Integer coins;
        public String session;
        public String playerName;
        public Resp() {}
    }

    public record LoginResult(boolean ok, String msg, Integer playerId, Integer coins, String session, String playerName) {}
}
