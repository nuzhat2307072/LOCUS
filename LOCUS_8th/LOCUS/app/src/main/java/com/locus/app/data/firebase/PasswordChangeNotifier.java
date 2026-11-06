package com.locus.app.data.firebase;

import com.locus.app.BuildConfig;
import com.locus.app.net.HttpClient;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * Sends the "your password was changed" e-mail. An app cannot send e-mail by itself without a mail server,
 * so a free e-mail API (EmailJS) is used: no server of your own is needed. It is configured with three values
 * in local.properties (EMAILJS_SERVICE_ID, EMAILJS_TEMPLATE_ID, EMAILJS_PUBLIC_KEY) - see the README.
 * Without them the password still changes, and the screen says that no e-mail was sent.
 */
public final class PasswordChangeNotifier {
    private PasswordChangeNotifier() {
    }

    public static boolean isConfigured() {
        return !BuildConfig.EMAILJS_SERVICE_ID.isEmpty() && !BuildConfig.EMAILJS_TEMPLATE_ID.isEmpty()
                && !BuildConfig.EMAILJS_PUBLIC_KEY.isEmpty();
    }

    /** Blocking. @return true when the e-mail service accepted the message. */
    public static boolean send(String toEmail, String name) {
        if (!isConfigured() || toEmail == null || toEmail.isEmpty()) return false;
        try {
            JSONObject params = new JSONObject();
            params.put("to_email", toEmail);
            params.put("to_name", name == null || name.isEmpty() ? toEmail : name);
            params.put("app_name", "LOCUS");
            params.put("time", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date()));
            params.put("message", "The password of your LOCUS account was just changed. "
                    + "If this was not you, reset your password immediately and log out of other devices.");
            JSONObject body = new JSONObject();
            body.put("service_id", BuildConfig.EMAILJS_SERVICE_ID);
            body.put("template_id", BuildConfig.EMAILJS_TEMPLATE_ID);
            body.put("user_id", BuildConfig.EMAILJS_PUBLIC_KEY);
            body.put("template_params", params);
            HttpClient.request("POST", "https://api.emailjs.com/api/v1.0/email/send", body.toString(),
                    "application/json", 10000, 20000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
