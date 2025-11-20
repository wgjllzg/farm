package org.example.demo;

import java.util.ArrayList;
import java.util.List;

public class Player {
    private int id;
    private String name;
    private String password;
    private int coins;
    private List<Integer> friends;

    public Player() {
        this.friends = new ArrayList<>();
    }

    public Player(int id, String name, String password, int coins) {
        this.id = id;
        this.name = name;
        this.password = password;
        this.coins = coins;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getPassword() { return password; }
    public int getCoins() { return coins; }
    public void setCoins(int coins) { this.coins = coins; }
    public void setId(int id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public List<Integer> getFriends() { return friends; }

    public boolean passwordEquals(String raw) {
        return password != null && password.equals(raw);
    }
}
