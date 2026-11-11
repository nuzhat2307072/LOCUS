package com.locus.app.model;

/** One message of a request chat (chats/{chatId}/messages/{id}). */
public class ChatMessage {
    public String id = "";
    public String from = "";
    public String text = "";
    public long at;

    public ChatMessage() {
    }

    public ChatMessage(String id, String from, String text, long at) {
        this.id = id;
        this.from = from;
        this.text = text;
        this.at = at;
    }
}
