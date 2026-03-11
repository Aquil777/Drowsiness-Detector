package com.example.drowsinessdetector;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Guarda e carrega o histórico das últimas sessões em SharedPreferences (via JSON).
 * Responsabilidade única: persistência de DriveSession.
 */
public class SessionRepository {

    private static final String PREFS_NAME    = "SessionHistory";
    private static final String KEY_SESSIONS  = "sessions";
    private static final int    MAX_SESSIONS  = 5;

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public SessionRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveSession(DriveSession session) {
        if (session == null || session.getEventCount() == 0) return; // Não guarda sessões vazias

        List<DriveSession.SessionSnapshot> history = loadSnapshots();
        history.add(0, session.toSnapshot()); // Mais recente primeiro

        // Mantém só as últimas MAX_SESSIONS
        if (history.size() > MAX_SESSIONS) {
            history = history.subList(0, MAX_SESSIONS);
        }

        prefs.edit().putString(KEY_SESSIONS, gson.toJson(history)).apply();
    }

    public List<DriveSession.SessionSnapshot> loadSnapshots() {
        String json = prefs.getString(KEY_SESSIONS, null);
        if (json == null) return new ArrayList<>();
        Type type = new TypeToken<List<DriveSession.SessionSnapshot>>(){}.getType();
        List<DriveSession.SessionSnapshot> result = gson.fromJson(json, type);
        return result != null ? result : new ArrayList<>();
    }

    public void clearAll() {
        prefs.edit().remove(KEY_SESSIONS).apply();
    }
}