package com.GGGlavfh.kookbind;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.UUID;


public final class Placeholders {

    //唯一的标识符：%kook_bound% / %kook_uid%
    private static final String IDENTIFIER = "kook";
    private static final String PARAM_BOUND = "bound";
    private static final String PARAM_UID = "uid";

    private final Database database;
    private final String author;
    private final String version;
    private Expansion expansion;

    public Placeholders(Database database, String author, String version) {
        this.database = database;
        this.author = author;
        this.version = version;
    }

    //注册变量；返回是否注册成功
    public boolean register() {
        expansion = new Expansion();
        return expansion.register();
    }

    public void unregister() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
    }

    String resolve(UUID uuid, String params) {
        if (uuid == null || params == null) {
            return null;
        }
        String key = params.trim().toLowerCase(java.util.Locale.ROOT);
        if (PARAM_BOUND.equals(key)) {
            return database.isBound(uuid) ? "true" : "false";
        }
        if (PARAM_UID.equals(key)) {
            String kookId = database.getKookId(uuid);
            return kookId == null ? "" : kookId;
        }
        return null;
    }

    private final class Expansion extends PlaceholderExpansion {

        @Override
        public String getIdentifier() {
            return IDENTIFIER;
        }

        @Override
        public String getAuthor() {
            return author;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, String params) {
            return resolve(player == null ? null : player.getUniqueId(), params);
        }
    }
}
