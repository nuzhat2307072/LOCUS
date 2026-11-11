package com.locus.app.ui;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.locus.app.R;
import com.locus.app.data.firebase.HazardRepository;
import com.locus.app.model.Hazard;
import com.locus.app.model.HazardTrust;
import com.locus.app.model.HazardType;
import com.locus.app.util.GeoUtils;
import com.locus.app.data.firebase.Session;

import java.text.DateFormat;
import java.util.Date;

/**
 * The hazard details dialog, shared by the navigation screen and the hazards screen: what, when, how many people
 * near the place agree, and the "Still there" / "Cleared" buttons (only for people who are near the place).
 */
public final class HazardDialogs {
    private HazardDialogs() {
    }

    /** One line saying whether people near the place have confirmed the report. */
    public static String verificationLine(Activity a, Hazard h) {
        if (h.isVerified()) return a.getString(R.string.hazard_verified_line, h.nearStill);
        if (h.nearCleared > h.nearStill) return a.getString(R.string.hazard_disputed_line, h.nearCleared);
        return a.getString(R.string.hazard_unverified_line, h.nearStill, HazardTrust.VERIFY_MIN);
    }

    public static int verificationColor(Activity a, Hazard h) {
        if (h.isVerified()) return ContextCompat.getColor(a, R.color.locus_ok);
        if (h.nearCleared > h.nearStill) return ContextCompat.getColor(a, R.color.locus_danger);
        return ContextCompat.getColor(a, R.color.locus_text_secondary);
    }

    /** @param onChanged runs after a vote that may change the hazards on the map (cleared / removed); may be null */
    public static AlertDialog show(final Activity a, final Hazard h, @Nullable final Runnable onChanged) {
        final HazardType t = HazardType.fromName(h.type);
        final boolean mine = h.reporterUid != null && h.reporterUid.equals(Session.uid(a));
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(h.createdAt));
        String until = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(h.expiresAt()));
        View v = a.getLayoutInflater().inflate(R.layout.dialog_hazard, null);
        TextView tvDetails = v.findViewById(R.id.tvHazardDetails);
        TextView tvConf = v.findViewById(R.id.tvHazardConfidence);
        final TextView tvRep = v.findViewById(R.id.tvHazardReporter);
        View btnStill = v.findViewById(R.id.btnVoteStill);
        View btnCleared = v.findViewById(R.id.btnVoteCleared);
        View btnRemove = v.findViewById(R.id.btnHazardRemove);

        tvDetails.setText(a.getString(R.string.hazard_details, h.radiusM,
                a.getString(t.blocking ? R.string.effect_closed : R.string.effect_slower), when,
                mine ? a.getString(R.string.by_you) : "") + "\n" + a.getString(R.string.hazard_expires, until));
        tvConf.setText(verificationLine(a, h) + "\n" + a.getString(R.string.hazard_votes_line, h.stillVotes, h.clearedVotes));
        tvConf.setTextColor(verificationColor(a, h));
        tvRep.setText("");
        HazardRepository.fetchReputation(a, h.reporterUid, pts -> {
            if (!a.isDestroyed()) tvRep.setText(a.getString(R.string.reporter_reputation, pts));
        });

        final AlertDialog dialog = new AlertDialog.Builder(a).setTitle(t.labelRes).setView(v)
                .setPositiveButton(android.R.string.ok, null).create();
        final double dist = HazardRepository.distanceToMe(a, h);
        final boolean near = HazardTrust.canVote(dist);
        if (mine) { // you cannot vote on your own report; you can only withdraw it
            btnStill.setVisibility(View.GONE);
            btnCleared.setVisibility(View.GONE);
        } else if (!near) { // only people around the place can confirm it, that is what makes a report trustworthy
            btnStill.setEnabled(false);
            btnCleared.setEnabled(false);
            tvRep.setText(tvRep.getText() + (tvRep.length() > 0 ? "\n" : "")
                    + (dist < 0 ? a.getString(R.string.hazard_far_unknown)
                    : a.getString(R.string.hazard_far, GeoUtils.distanceText(a, dist), HazardTrust.VOTE_RADIUS_M)));
        } else {
            btnStill.setEnabled(!"still".equals(h.myVote));
            btnCleared.setEnabled(!"cleared".equals(h.myVote));
            btnStill.setOnClickListener(x -> {
                vote(a, h, true, onChanged);
                dialog.dismiss();
            });
            btnCleared.setOnClickListener(x -> {
                vote(a, h, false, onChanged);
                dialog.dismiss();
            });
        }
        // Withdrawing your own report, or clearing a hazard that is gone (only from near the place).
        if (!mine && !near) {
            btnRemove.setVisibility(View.GONE);
        }
        btnRemove.setOnClickListener(x -> {
            dialog.dismiss();
            new AlertDialog.Builder(a)
                    .setMessage(R.string.remove_hazard_confirm)
                    .setPositiveButton(R.string.remove_hazard, (d2, w2) -> {
                        HazardRepository.delete(a, h);
                        Toast.makeText(a, R.string.hazard_removed, Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        dialog.show();
        return dialog;
    }

    /** Casts the vote and tells the person whether it was taken. */
    public static void vote(Activity a, Hazard h, boolean still, @Nullable Runnable onChanged) {
        boolean taken = HazardRepository.vote(a, h, still);
        Toast.makeText(a, taken ? R.string.vote_thanks : R.string.hazard_far_unknown, Toast.LENGTH_SHORT).show();
        if (taken && !still && onChanged != null) onChanged.run();
    }
}
