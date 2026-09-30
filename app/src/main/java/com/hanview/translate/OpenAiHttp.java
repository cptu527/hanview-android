package com.hanview.translate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

public final class OpenAiHttp {
    private static final String[] DOH_IPS =
            new String[]{
                    "1.1.1.1",
                    "1.0.0.1"
            };

    private static final OkHttpClient CLIENT =
            new OkHttpClient.Builder()
                    .dns(OpenAiHttp::lookup)
                    .connectTimeout(
                            12,
                            TimeUnit.SECONDS
                    )
                    .readTimeout(
                            50,
                            TimeUnit.SECONDS
                    )
                    .writeTimeout(
                            20,
                            TimeUnit.SECONDS
                    )
                    .retryOnConnectionFailure(true)
                    .build();

    private OpenAiHttp() {
    }

    public static OkHttpClient client() {
        return CLIENT;
    }

    private static List<InetAddress> lookup(
            String hostname
    ) throws UnknownHostException {
        try {
            return Dns.SYSTEM.lookup(hostname);
        } catch (UnknownHostException systemFailure) {
            List<InetAddress> fallback =
                    resolveViaDoh(hostname);

            if (!fallback.isEmpty()) {
                return fallback;
            }

            UnknownHostException out =
                    new UnknownHostException(
                            hostname
                                    + " (system DNS and secure fallback both failed)"
                    );
            out.initCause(systemFailure);
            throw out;
        }
    }

    private static List<InetAddress> resolveViaDoh(
            String hostname
    ) {
        List<InetAddress> out =
                new ArrayList<>();

        for (String resolverIp : DOH_IPS) {
            HttpsURLConnection conn = null;

            try {
                String encoded =
                        URLEncoder.encode(
                                hostname,
                                StandardCharsets.UTF_8.name()
                        );

                java.net.URL url =
                        new java.net.URL(
                                "https://"
                                        + resolverIp
                                        + "/dns-query?name="
                                        + encoded
                                        + "&type=A"
                        );

                conn =
                        (HttpsURLConnection)
                                url.openConnection();

                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty(
                        "Accept",
                        "application/dns-json"
                );
                conn.setRequestProperty(
                        "User-Agent",
                        "ViewNyang/1.0"
                );

                int code =
                        conn.getResponseCode();

                if (code < 200
                        || code >= 300) {
                    continue;
                }

                StringBuilder body =
                        new StringBuilder();

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             conn.getInputStream(),
                                             StandardCharsets.UTF_8
                                     )
                             )) {
                    String line;

                    while ((line = reader.readLine())
                            != null) {
                        body.append(line);
                    }
                }

                JSONObject root =
                        new JSONObject(
                                body.toString()
                        );

                if (root.optInt(
                        "Status",
                        -1
                ) != 0) {
                    continue;
                }

                JSONArray answers =
                        root.optJSONArray(
                                "Answer"
                        );

                if (answers == null) {
                    continue;
                }

                for (int i = 0;
                     i < answers.length();
                     i++) {
                    JSONObject answer =
                            answers.optJSONObject(i);

                    if (answer == null
                            || answer.optInt(
                            "type",
                            0
                    ) != 1) {
                        continue;
                    }

                    String data =
                            answer.optString(
                                    "data",
                                    ""
                            ).trim();

                    if (!looksLikeIpv4(data)) {
                        continue;
                    }

                    out.add(
                            InetAddress.getByName(
                                    data
                            )
                    );
                }

                if (!out.isEmpty()) {
                    return out;
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        return out;
    }

    private static boolean looksLikeIpv4(
            String value
    ) {
        if (value == null
                || value.isEmpty()) {
            return false;
        }

        String[] parts =
                value.split("\\.");

        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            try {
                int number =
                        Integer.parseInt(part);

                if (number < 0
                        || number > 255) {
                    return false;
                }
            } catch (Exception ignored) {
                return false;
            }
        }

        return true;
    }
}
