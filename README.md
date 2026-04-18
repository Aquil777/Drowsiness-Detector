# Drowsy Detector 

**PT:** Aplicação Android desenvolvida como Trabalho de Conclusão de Curso (TCC) em Engenharia Informática no ISCTEM, Moçambique. Detecta sinais de fadiga em condutores em tempo real através da câmara frontal do smartphone, utilizando um modelo de Deep Learning baseado na arquitectura MobileNetV2.

**EN:** Android application developed as a final year project (TCC) in Computer Engineering at ISCTEM, Mozambique. Detects driver fatigue signs in real time using the smartphone's front camera and a Deep Learning model based on the MobileNetV2 architecture.

---

## Características Principais / Key Features

**PT**
- **Detecção Facial Inteligente:** O ML Kit valida a presença do condutor antes de cada inferência, evitando falsos positivos por ausência de rosto
- **Análise em Tempo Real:** Processamento integralmente local (Edge Computing) — sem envio de dados para servidores externos, garantindo privacidade e funcionamento offline
- **Modelo Optimizado:** MobileNetV2 fine-tuned com 78.308 imagens (NTHU-DDD + UTA-RLDD), atingindo 91% de accuracy e 92% de recall na classe fadiga
- **Suavização de Score:** Buffer de 5 frames para eliminar variações abruptas e reduzir falsos positivos
- **Alertas Multimodais:** Alarme sonoro, vibração (funciona em modo silencioso) ou mensagens de voz sintetizadas (TTS)
- **Sensibilidade Ajustável:** Threshold configurável (0.0–1.0) com sugestão automática em condições de pouca luz
- **Relatórios de Sessão:** Histórico das últimas 5 sessões com hora de início/fim, número de alertas, duração e score médio
- **Onboarding:** Ecrã de ajuda na primeira abertura, acessível a qualquer momento nas Configurações

**EN**
- **Smart Face Detection:** ML Kit validates driver presence before each inference, preventing false positives when no face is detected
- **Real-Time Analysis:** Fully on-device processing (Edge Computing) — no data sent to external servers, ensuring privacy and offline operation
- **Optimised Model:** MobileNetV2 fine-tuned on 78,308 images (NTHU-DDD + UTA-RLDD), achieving 91% accuracy and 92% fatigue recall
- **Score Smoothing:** 5-frame buffer to eliminate abrupt variations and reduce false positives
- **Multimodal Alerts:** Sound alarm, vibration (works in silent mode) or synthesized voice messages (TTS)
- **Adjustable Sensitivity:** Configurable threshold (0.0–1.0) with automatic suggestion in low-light conditions
- **Session Reports:** History of last 5 sessions with start/end time, alert count, duration and average score
- **Onboarding:** Help screen on first launch, accessible at any time from Settings

---

## Tecnologias / Technologies

| Categoria | Tecnologia |
|---|---|
| Linguagem / Language | Java (Android SDK) |
| Câmara / Camera | Jetpack CameraX |
| Detecção Facial / Face Detection | ML Kit Face Detection API |
| Motor de IA / AI Engine | TensorFlow Lite + NNAPI/GPU delegate |
| Arquitectura do Modelo / Model Architecture | MobileNetV2 (fine-tuned, 168×168) |
| Interface / UI | Material Design Components |
| Persistência / Storage | SharedPreferences + Gson |
| Alertas / Alerts | MediaPlayer, TextToSpeech, Vibrator |
| Treino / Training | Python, TensorFlow/Keras, Google Colab |

---

## Arquitectura / Architecture

O sistema processa cada frame segundo um pipeline sequencial:

```
CameraX → ML Kit (face?) → Center Crop 168×168 → Normalização [-1,+1] → TFLite → Score → Suavização → Threshold → Alerta
```

A arquitectura é um Grafo Acíclico Dirigido (DAG) onde cada frame é processado de forma volátil — descartado imediatamente após a inferência, sem armazenamento persistente de dados biométricos.

---

## Resultados do Modelo / Model Results

| Métrica | Valor |
|---|---|
| Accuracy (test set, 4.234 imagens) | 91% |
| Precision — Fadiga | 90% |
| Recall — Fadiga | 92% |
| F1-Score — Fadiga | 0.91 |
| Threshold utilizado | 0.45 |
| Latência de inferência (dispositivo médio) | ~100–170ms |

Datasets utilizados: **NTHU-DDD** + **UTA-RLDD** (78.308 imagens, split 85/10/5)

---

## Instalação / Installation

1. Transfere o APK da secção [Releases](../../releases)
2. No telemóvel, vai a **Definições → Segurança → Instalar de fontes desconhecidas**
3. Abre o APK e instala
4. Concede permissão de câmara quando solicitado

**Requisitos mínimos:** Android 8.0 (API 26) | Câmara frontal | 100MB de armazenamento livre

---

## Versão / Version

`v3.0 — Alpha` — Em desenvolvimento activo. Podem ocorrer falsos positivos. Feedback bem-vindo.

---

## Aviso / Disclaimer

**PT:** Esta aplicação é um auxílio à condução segura. Nunca substitui pausas regulares nem a atenção do condutor. Se sentires sonolência, para em local seguro.

**EN:** This application is a driving safety aid. It never replaces regular breaks or driver attention. If you feel drowsy, stop in a safe location.
