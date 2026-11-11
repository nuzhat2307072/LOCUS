package com.locus.app.model;

/** One row of the chats list (userChats/{uid}/{chatId}). */
public class ChatSummary {
    public String chatId = "";
    public String requestId = "";
    public String title = "";
    public String otherName = "";
    public String otherUid = "";
    public String last = "";
    public String lastFrom = "";
    public long lastAt;
    public int unread;
    public boolean closed;
}
