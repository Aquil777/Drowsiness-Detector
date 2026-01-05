package com.example.drowsinessdetector;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch swVoice = findViewById(R.id.switchVoiceMode);
        SharedPreferences prefs = getSharedPreferences("Configs", MODE_PRIVATE);

        // Carrega escolha anterior
        swVoice.setChecked(prefs.getBoolean("useVoice", false));

        swVoice.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("useVoice", isChecked).apply();
        });
    }
}