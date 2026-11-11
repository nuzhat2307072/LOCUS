package com.locus.app.model;

/** Somebody asks to message me, usually about one of my Community posts. */
public class ContactRequest {
    public String fromUid = "";
    public String fromName = "";
    public String postId = "";
    public String postTitle = "";
    public String note = "";
    public long at;
    public String status = ContactRules.STATUS_PENDING;
}
