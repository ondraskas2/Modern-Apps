import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the calendar app's bundled holiday data from Thunderbird's public
 * holiday calendars (https://www.thunderbird.net/en-US/calendar/holidays/).
 *
 * Downloads the index page, picks one .ics per country (preferring the English
 * variant), parses each into date-sorted {"d","n"} entries, and writes
 * assets/holidays/&lt;code&gt;.json plus index.json. No app-side dependency and
 * no network at runtime.
 */
public final class HolidayGen {
    private static final String BASE = "https://www.thunderbird.net";
    private static final String INDEX = BASE + "/en-US/calendar/holidays/";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    public static void main(String[] args) throws Exception {
        File outDir = new File("calendar/src/main/assets/holidays");
        // Start clean so removed countries don't leave stale files.
        if (outDir.exists()) {
            File[] old = outDir.listFiles((d, n) -> n.endsWith(".json"));
            if (old != null) for (File f : old) f.delete();
        } else if (!outDir.mkdirs()) {
            throw new IllegalStateException("Could not create " + outDir.getAbsolutePath());
        }

        String html = get(INDEX);
        // Map base-country-name -> chosen href (prefer the English variant).
        Map<String, String> chosen = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
            "<a[^>]*href=\"(/media/caldata/autogen/[^\"]+\\.ics)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL).matcher(html);
        while (m.find()) {
            String href = m.group(1);
            String label = m.group(2).replaceAll("<[^>]+>", "").trim();
            String base = label.replaceAll("\\s*\\(.*?\\)\\s*", "").trim(); // drop "(English)" etc.
            boolean english = label.toLowerCase().contains("english");
            if (!chosen.containsKey(base) || english) {
                chosen.put(base, href);
            }
        }

        List<String[]> index = new ArrayList<>(); // {code, name}
        for (Map.Entry<String, String> e : chosen.entrySet()) {
            String name = e.getKey();
            String code = name.replaceAll("[^A-Za-z0-9]", "");
            try {
                String ics = get(BASE + e.getValue());
                List<String[]> holidays = parseIcs(ics); // {date, summary}
                if (holidays.isEmpty()) {
                    System.out.println("Skipping " + name + " (no events)");
                    continue;
                }
                holidays.sort((a, b) -> {
                    int c = a[0].compareTo(b[0]);
                    return c != 0 ? c : a[1].compareToIgnoreCase(b[1]);
                });
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < holidays.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append("{\"d\":\"").append(holidays.get(i)[0]).append("\",\"n\":")
                        .append(jsonString(holidays.get(i)[1])).append('}');
                }
                sb.append(']');
                Files.write(new File(outDir, code + ".json").toPath(),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
                index.add(new String[]{code, name});
                System.out.println(name + " (" + holidays.size() + " entries)");
            } catch (Exception ex) {
                System.err.println("Skipping " + name + ": " + ex.getMessage());
            }
        }

        index.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        StringBuilder idx = new StringBuilder("[");
        for (int i = 0; i < index.size(); i++) {
            if (i > 0) idx.append(',');
            idx.append("{\"code\":\"").append(index.get(i)[0]).append("\",\"name\":")
                .append(jsonString(index.get(i)[1])).append('}');
        }
        idx.append(']');
        Files.write(new File(outDir, "index.json").toPath(),
            idx.toString().getBytes(StandardCharsets.UTF_8));

        System.out.println("Wrote " + index.size() + " countries to " + outDir.getAbsolutePath());
    }

    /** Parse VEVENTs into {isoDate, summary}. Handles line folding and all-day DATE values. */
    private static List<String[]> parseIcs(String ics) {
        List<String> lines = unfold(ics);
        List<String[]> out = new ArrayList<>();
        boolean inEvent = false;
        String summary = null;
        String date = null;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) {
                inEvent = true; summary = null; date = null; continue;
            }
            if (line.equals("END:VEVENT")) {
                if (summary != null && date != null) out.add(new String[]{date, summary});
                inEvent = false; continue;
            }
            if (!inEvent) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String left = line.substring(0, colon).toUpperCase();
            String value = line.substring(colon + 1);
            if (left.equals("SUMMARY")) {
                summary = unescape(value);
            } else if (left.startsWith("DTSTART")) {
                Matcher d = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(value);
                if (d.find()) date = d.group(1) + "-" + d.group(2) + "-" + d.group(3);
            }
        }
        return out;
    }

    /** Unfold RFC 5545 folded lines (continuations begin with space or tab). */
    private static List<String> unfold(String ics) {
        List<String> out = new ArrayList<>();
        for (String raw : ics.split("\\r?\\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else {
                out.add(raw);
            }
        }
        return out;
    }

    private static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': case 'N': b.append(' '); break;
                    case ',': b.append(','); break;
                    case ';': b.append(';'); break;
                    case '\\': b.append('\\'); break;
                    default: b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (holidaygen)")
            .timeout(Duration.ofSeconds(60))
            .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private static String jsonString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
