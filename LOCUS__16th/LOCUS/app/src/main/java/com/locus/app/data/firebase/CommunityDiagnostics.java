package com.locus.app.data.firebase;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.Source;
import com.locus.app.R;
import com.locus.app.data.db.LocusDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * "Check connection": tells the person, in plain words, why posts do or do not reach other phones.
 * It really writes a small test document to the server and reads it back, so a green result means the
 * whole path (internet, login, database, published rules) works.
 */
public final class CommunityDiagnostics {
    public interface Callback {
        void onDone(String report, boolean allGood);
    }

    private CommunityDiagnostics() {
    }

    public static void run(Context context, Callback cb) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            List<String> lines = new ArrayList<>();
            boolean ok = check(app, lines);
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append("\n\n");
            if (ok) sb.append(app.getString(R.string.diag_all_good)).append("\n\n");
            sb.append(app.getString(R.string.diag_battery_tip));
            new Handler(Looper.getMainLooper()).post(() -> cb.onDone(sb.toString(), ok));
        }, "locus-diagnostics").start();
    }

    private static String good(Context c, int res, Object... a) {
        return "✅ " + c.getString(res, a);
    }

    private static String bad(Context c, int res, Object... a) {
        return "❌ " + c.getString(res, a);
    }

    private static boolean check(Context c, List<String> out) {
        if (!Session.isFirebaseReady(c)) {
            out.add(bad(c, R.string.diag_firebase_missing));
            return false;
        }
        out.add(good(c, R.string.diag_firebase_ok));

        if (!Session.canSyncOnline(c)) {
            out.add(bad(c, R.string.diag_not_signed_in));
            return false;
        }
        out.add(good(c, R.string.diag_signed_in));

        if (!hasInternet(c)) {
            out.add(bad(c, R.string.diag_no_internet));
            return false;
        }
        out.add(good(c, R.string.diag_internet_ok));

        boolean ok;
        try {
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            String uid = Session.uid(c);
            Map<String, Object> m = new HashMap<>();
            m.put("checkedAt", FieldValue.serverTimestamp());
            Tasks.await(db.collection("diagnostics").document(uid).set(m), 20, TimeUnit.SECONDS);
            Tasks.await(db.collection("diagnostics").document(uid).get(Source.SERVER), 20, TimeUnit.SECONDS);
            Tasks.await(db.collection(CommunityRepository.COLLECTION).limit(1).get(Source.SERVER), 20, TimeUnit.SECONDS);
            out.add(good(c, R.string.diag_server_ok));
            ok = true;
        } catch (ExecutionException e) {
            ok = false;
            Throwable t = e.getCause();
            CommunitySync.State s = t instanceof FirebaseFirestoreException
                    ? CommunitySync.stateFor((FirebaseFirestoreException) t) : CommunitySync.State.ERROR;
            switch (s) {
                case NO_PERMISSION:
                    out.add(bad(c, R.string.diag_server_denied));
                    break;
                case NO_DATABASE:
                    out.add(bad(c, R.string.diag_server_nodb));
                    break;
                default:
                    out.add(bad(c, R.string.diag_server_fail));
                    break;
            }
        } catch (Exception e) { // timeout or interrupted
            ok = false;
            out.add(bad(c, R.string.diag_server_fail));
        }

        // Realtime Database: chat, live sharing and hazard votes live here
        boolean rtdbOk = checkRealtime(c, out);
        ok = ok && rtdbOk;

        int waiting = LocusDatabase.get(c).requestDao().unsyncedIds().size();
        out.add(waiting == 0 ? good(c, R.string.diag_nothing_waiting) : bad(c, R.string.diag_waiting, waiting));
        return ok && waiting == 0;
    }

    private static boolean checkRealtime(Context c, List<String> out) {
        String url = Rtdb.url();
        if (url == null) {
            out.add(bad(c, R.string.diag_rtdb_nourl));
            return false;
        }
        try {
            Tasks.await(Rtdb.get().getReference("userChats").child(Session.uid(c)).get(), 20, TimeUnit.SECONDS);
            out.add(good(c, R.string.diag_rtdb_ok, url));
            return true;
        } catch (ExecutionException e) {
            String why = ChatRepository.classify(e.getCause() instanceof Exception ? (Exception) e.getCause() : e);
            out.add(bad(c, "rules".equals(why) ? R.string.diag_rtdb_denied : R.string.diag_rtdb_fail, url));
            return false;
        } catch (Exception e) {
            out.add(bad(c, R.string.diag_rtdb_fail, url));
            return false;
        }
    }

    private static boolean hasInternet(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
