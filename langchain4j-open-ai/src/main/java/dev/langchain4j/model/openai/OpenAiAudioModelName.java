package dev.langchain4j.model.openai;

public enum OpenAiAudioModelName {

    WHISPER_1("whisper-1"),
    WHISPER_2("whisper-2"),

    private final String stringValue;

    OpenAiAudioModelName(String stringValue) {
        this.stringValue = stringValue;
    }

    @Override
    public String toString() {
        return stringValue;
    }
}
