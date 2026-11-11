package com.locus.app.model;

/** A person saved in my contacts (after a contact request was accepted). */
public class Contact {
    public String uid = "";
    public String name = "";
    public long since;
    /** The post the two people first got in touch about (may be empty). */
    public String postTitle = "";
}
