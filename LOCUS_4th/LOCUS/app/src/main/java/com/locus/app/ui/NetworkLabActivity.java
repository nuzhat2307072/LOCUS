package com.locus.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.appcompat.app.AppCompatActivity;

import com.locus.app.databinding.ActivityNetworkLabBinding;
import com.locus.app.net.HttpClient;

import org.json.JSONObject;

/**
 * REST playground against the public JSONPlaceholder test API. Demonstrates GET / POST / PUT / DELETE,
 * JSON request bodies, status codes, error handling and a loading indicator.
 */
public class NetworkLabActivity extends AppCompatActivity {
    private static final String BASE = "https://jsonplaceholder.typicode.com/posts";
    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE"};

    private ActivityNetworkLabBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityNetworkLabBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        b.spMethod.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, METHODS));
        b.btnSend.setOnClickListener(v -> send((String) b.spMethod.getSelectedItem()));
    }

    private void send(final String method) {
        final String url;
        final String body;
        switch (method) {
            case "POST":
                url = BASE;
                body = payload(null);
                break;
            case "PUT":
                url = BASE + "/1";
                body = payload(1);
                break;
            case "DELETE":
                url = BASE + "/1";
                body = null;
                break;
            default:
                url = BASE + "/1";
                body = null;
        }
        b.progress.setVisibility(View.VISIBLE);
        b.btnSend.setEnabled(false);
        b.tvOutput.setText(method + " " + url + (body == null ? "" : "\n\nRequest body:\n" + body) + "\n\n...");

        HttpClient.async(this, () -> HttpClient.request(method, url, body, "application/json; charset=utf-8"),
                new HttpClient.Callback<HttpClient.Response>() {
                    @Override
                    public void onSuccess(HttpClient.Response r) {
                        done();
                        String pretty = r.body;
                        try {
                            pretty = new JSONObject(r.body).toString(2); // JSON parsing
                        } catch (Exception ignored) {
                            // not a JSON object (e.g. empty body) - show raw text
                        }
                        b.tvOutput.setText(method + " " + url + "\nHTTP " + r.code + "\n\n" + pretty);
                    }

                    @Override
                    public void onError(String message) {
                        done();
                        b.tvOutput.setText(method + " " + url + "\n\nError: " + message);
                    }
                });
    }

    private void done() {
        b.progress.setVisibility(View.INVISIBLE);
        b.btnSend.setEnabled(true);
    }

    private static String payload(Integer id) {
        try {
            JSONObject o = new JSONObject();
            if (id != null) o.put("id", id);
            o.put("title", "LOCUS test post");
            o.put("body", "Sent from the LOCUS network lab");
            o.put("userId", 1);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
