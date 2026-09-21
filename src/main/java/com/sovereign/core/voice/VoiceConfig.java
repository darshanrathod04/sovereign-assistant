package com.sovereign.core.voice;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * <b>VoiceConfig</b>
 *
 * <p>Configuration settings for ambient voice interactions, speech-to-text,
 * text-to-speech synthesis, and wake-word triggers.</p>
 */
public class VoiceConfig {

    public static final Set<String> DEFAULT_WAKE_WORDS = Set.of("hey sovereign", "jarvis", "sovereign");

    private int voiceRate = 0; // -10 (slow) to +10 (fast), 0 is default
    private int volume = 100; // 0 to 100
    private final Set<String> wakeWords = new LinkedHashSet<>();
    private boolean audioEnabled = true;
    private String sttEngine = "whisper";
    private String ttsEngine = "platform";

    public VoiceConfig() {
        this.wakeWords.addAll(DEFAULT_WAKE_WORDS);
    }

    public static VoiceConfig defaultConfig() {
        return new VoiceConfig();
    }

    public static VoiceConfig silentConfig() {
        VoiceConfig config = new VoiceConfig();
        config.setAudioEnabled(false);
        config.setTtsEngine("silent");
        return config;
    }

    public int getVoiceRate() {
        return voiceRate;
    }

    public void setVoiceRate(int voiceRate) {
        this.voiceRate = Math.max(-10, Math.min(10, voiceRate));
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
    }

    public Set<String> getWakeWords() {
        return Collections.unmodifiableSet(wakeWords);
    }

    public void addWakeWord(String wakeWord) {
        if (wakeWord != null && !wakeWord.trim().isEmpty()) {
            this.wakeWords.add(wakeWord.trim().toLowerCase(Locale.ROOT));
        }
    }

    public void removeWakeWord(String wakeWord) {
        if (wakeWord != null) {
            this.wakeWords.remove(wakeWord.trim().toLowerCase(Locale.ROOT));
        }
    }

    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    public void setAudioEnabled(boolean audioEnabled) {
        this.audioEnabled = audioEnabled;
    }

    public String getSttEngine() {
        return sttEngine;
    }

    public void setSttEngine(String sttEngine) {
        this.sttEngine = sttEngine;
    }

    public String getTtsEngine() {
        return ttsEngine;
    }

    public void setTtsEngine(String ttsEngine) {
        this.ttsEngine = ttsEngine;
    }

    /**
     * Checks if the given text begins with or contains any configured wake-word.
     */
    public boolean containsWakeWord(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT);
        for (String wake : wakeWords) {
            if (lower.startsWith(wake) || lower.contains(wake)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strips leading wake-words and common punctuation/salutations from input.
     */
    public String stripWakeWord(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.trim();
        for (String wake : wakeWords) {
            String pattern = "(?i)^" + java.util.regex.Pattern.quote(wake) + "[,:]?\\s*";
            cleaned = cleaned.replaceFirst(pattern, "");
        }
        return cleaned.trim();
    }
}
