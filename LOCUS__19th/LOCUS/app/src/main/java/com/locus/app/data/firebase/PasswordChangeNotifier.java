package com.locus.app.data.firebase;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;

/**
 * Sends the "your password was changed" e-mail. An app cannot send e-mail by itself without a mail server,
 * so a free e-mail API (EmailJS) is used: no server of your own is needed. The three values (service id,
 * template id, public key) are typed into the app (Connect Firebase screen) - see {@link EmailConfig} and the README.
 * Without them the password still changes, and the screen says that no e-mail was sent.
 */
public final class PasswordChangeNotifier {
    private static final String URL = "https://api.emailjs.com/api/v1.0/email/send";

    private PasswordChangeNotifier() {
    }

    public static boolean isConfigured(Context c) {
        return EmailConfig.isConfigured(c);
    }

    /** Blocking. @return true when the e-mail service accepted the message. */
    public static boolean send(Context c, String toEmail, String name) {
        return post(c, toEmail, name, "The password of your LOCUS account was just changed. "
                + "If this was not you, reset your password immediately and log out of other devices.") == null;
    }

    /** Blocking. Sends a sample message to {@code toEmail}. @return null when sent, otherwise the reason it failed. */
    public static String sendTest(Context c, String toEmail) {
        return post(c, toEmail, "", "This is a test message from LOCUS. If you can read it, e-mail notices work.");
    }

    /** @return null on success, otherwise the error text from the e-mail service (or the network). */
    private static String post(Context c, String toEmail, String name, String message) {
        if (!isConfigured(c)) return "E-mail is not set up yet.";
        if (toEmail == null || toEmail.isEmpty()) return "No e-mail address.";
        HttpURLConnection con = null;
        try {
            JSONObject params = new JSONObject();
            params.put("to_email", toEmail);
            params.put("email", toEmail);           // some templates use {{email}} for the recipient
            params.put("to_name", name == null || name.isEmpty() ? toEmail : name);
            params.put("app_name", "LOCUS");
            params.put("time", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date()));
            params.put("message", message);
            JSONObject body = new JSONObject();
            body.put("service_id", EmailConfig.serviceId(c));
            body.put("template_id", EmailConfig.templateId(c));
            body.put("user_id", EmailConfig.publicKey(c));
            body.put("template_params", params);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            con = (HttpURLConnection) new URL(URL).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            con.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = con.getOutputStream()) {
                os.write(bytes);
            }
            int code = con.getResponseCode();
            if (code >= 200 && code < 300) return null;
            // EmailJS explains what is wrong in the body: "The Public Key is invalid", "Template ID not found",
            // "API calls are disabled for non-browser applications" ...
            InputStream err = con.getErrorStream();
            String text = "";
            if (err != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null && sb.length() < 300) sb.append(line);
                    text = sb.toString();
                }
            }
            return "HTTP " + code + (text.isEmpty() ? "" : ": " + text);
        } catch (Exception e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
