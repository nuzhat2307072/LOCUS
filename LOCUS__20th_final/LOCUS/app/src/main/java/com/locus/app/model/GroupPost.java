package com.locus.app.model;

/** One post in a family-and-friends group (a message, photo, plan, help request, or a shared Community post / SOS). */
public class GroupPost {
    public String id = "";
    public String groupId = "";
    public String type = GroupPostRules.TEXT;
    public String uid = "";
    public String name = "";
    public long at;
    public String text = "";
    public String title = "";
    /** Start of a trip or party (UTC milliseconds), 0 when not set. */
    public long when;
    public String place = "";
    public double lat;
    public double lon;
    public boolean hasLocation;
    /** Help kind (donation, volunteer, blood, other) or the Community request type. */
    public String kind = "";
    public boolean hasPhoto;
    /** The Community request this was shared from. */
    public String ref = "";
}
