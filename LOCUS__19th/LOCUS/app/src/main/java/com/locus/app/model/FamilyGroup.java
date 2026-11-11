package com.locus.app.model;

import java.util.ArrayList;
import java.util.List;

/** A family-and-friends group. The lists are only filled for the detail screen. */
public class FamilyGroup {
    public String id = "";
    public String name = "";
    public String role = GroupRules.MEMBER;
    public String adminUid = "";
    public String adminName = "";
    public String code = "";
    public long createdAt;
    public final List<Member> members = new ArrayList<>();
    public final List<JoinRequest> requests = new ArrayList<>();

    public boolean isAdmin() {
        return GroupRules.isAdmin(role);
    }

    public static class Member {
        public String uid = "";
        public String name = "";
        public String role = GroupRules.MEMBER;
        public long since;
    }

    public static class JoinRequest {
        public String groupId = "";
        public String groupName = "";
        public String uid = "";
        public String name = "";
        public long at;
    }
}
