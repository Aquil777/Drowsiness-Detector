package com.example.drowsinessdetector;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DriveSession {

    public static class SessionSnapshot {
        public String date;
        public String endDate;
        public long   durationSeconds;
        public int    alertCount;
        public List<FatigueEvent> events;

        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📅 %s  →  %s\n⏱ %s  |  ⚠️ %d alertas\n",
                    date,
                    endDate != null ? endDate : "—",
                    formatDurationStatic(durationSeconds),
                    alertCount));
            if (events != null) {
                for (FatigueEvent e : events) sb.append("   ").append(e).append("\n");
            }
            return sb.toString();
        }

        private String formatDurationStatic(long seconds) {
            if (seconds < 60) return seconds + "s";
            return String.format(Locale.getDefault(), "%dm%02ds", seconds / 60, seconds % 60);
        }
    }

    public static class FatigueEvent {
        public String timestamp;
        public float  score;
        public long   durationMs;

        FatigueEvent() {}

        FatigueEvent(float score, long durationMs) {
            this.timestamp  = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            this.score      = score;
            this.durationMs = durationMs;
        }

        @Override
        public String toString() {
            return String.format(Locale.getDefault(),
                    "[%s]  Score: %.2f  |  %dms", timestamp, score, durationMs);
        }
    }

    private final List<FatigueEvent> events    = new ArrayList<>();
    private final long               startTime = System.currentTimeMillis();
    private final String             startDate =
            new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date());

    public void recordEvent(float score, long durationMs) {
        events.add(new FatigueEvent(score, durationMs));
    }

    public int  getEventCount()     { return events.size(); }
    public long getElapsedSeconds() { return (System.currentTimeMillis() - startTime) / 1000; }

    public float getAverageScore() {
        if (events.isEmpty()) return 0f;
        float sum = 0f;
        for (FatigueEvent e : events) sum += e.score;
        return sum / events.size();
    }

    public SessionSnapshot toSnapshot() {
        SessionSnapshot s = new SessionSnapshot();
        s.date            = startDate;
        s.endDate         = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        s.durationSeconds = getElapsedSeconds();
        s.alertCount      = events.size();
        s.events          = new ArrayList<>(events);
        return s;
    }
}