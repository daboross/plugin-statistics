package net.daboross.bukkitdev.pluginstatistics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.apache.commons.lang.Validate;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

public class PluginStatistics {

    private static final String API_URL_FORMAT = "https://dabo.guru/statistics/v1/%s/post";
    private static final long INTERVAL_SECONDS = 60 * 60; // Report every hour.
    private static final Object taskLock = new Object();

    private final Plugin plugin;
    private final UUID instanceUuid;
    private final boolean debug;
    private int taskId;

    public PluginStatistics(Plugin plugin, final boolean debug) {
        Validate.notNull(plugin);
        this.plugin = plugin;
        this.debug = debug;
        this.instanceUuid = UUID.randomUUID();
        this.taskId = -1;
    }

    public void start() {
        synchronized (taskLock) {
            if (taskId != -1) {
                return;
            }
            long intervalTicks = INTERVAL_SECONDS * 20;
            taskId = new ReportRunnable().runTaskTimerAsynchronously(plugin, intervalTicks, intervalTicks).getTaskId();
        }
    }

    public void stop() {
        synchronized (taskLock) {
            if (taskId == -1) {
                return;
            }
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public class ReportRunnable extends BukkitRunnable {

        @Override
        public void run() {
            String pluginName = plugin.getName();
            String pluginVersion = plugin.getDescription().getVersion();
            String serverVersion = plugin.getServer().getVersion();
            int onlinePlayers = 0;
            try {
                onlinePlayers = plugin.getServer().getOnlinePlayers().size();
            } catch (NoSuchMethodError ex) {
                try {
                    Method m = Server.class.getMethod("getOnlinePlayers");
                    onlinePlayers = ((Player[]) m.invoke(plugin.getServer())).length;
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e2) {
                    if (debug) {
                        plugin.getLogger().log(Level.WARNING, "[statistics] Unable to get online player count.", e2);
                    }
                }
            }

            Map<String, Object> dataMap = new HashMap<>(4);
            dataMap.put("instance_uuid", instanceUuid.toString());
            dataMap.put("plugin_version", pluginVersion);
            dataMap.put("server_version", serverVersion);
            dataMap.put("online_players", onlinePlayers);

            URL apiUrl;
            try {
                apiUrl = new URL(String.format(API_URL_FORMAT, URLEncoder.encode(pluginName, "UTF-8")));
            } catch (MalformedURLException | UnsupportedEncodingException ex) {
                if (debug) {
                    plugin.getLogger().log(Level.WARNING, "[statistics] Failed to encode API URL.", ex);
                }
                return;
            }

            byte[] encodedData;

            try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
                // By using an inner try-with-resources block, the outputstreamwriter will always be flushed&closed before
                // calling byteArrayOutpuStream.toByteArray().
                try (OutputStreamWriter outputStreamWriter = new OutputStreamWriter(byteArrayOutputStream, "UTF-8")) {
                    JsonSerialization.writeJsonObject(outputStreamWriter, dataMap);
                }
                encodedData = byteArrayOutputStream.toByteArray();
            } catch (IOException | JsonSerialization.JsonException ex) {
                if (debug) {
                    plugin.getLogger().log(Level.WARNING, "[statistics] Failed to encode and compress data to submit.", ex);
                }
                return;
            }

            HttpURLConnection connection;
            try {
                connection = (HttpURLConnection) apiUrl.openConnection();
            } catch (IOException | ClassCastException ex) {
                if (debug) {
                    plugin.getLogger().log(Level.WARNING, "[statistics] Failed to initiate connection.", ex);
                }
                return;
            }
            connection.addRequestProperty("Accept", "*/*");
            connection.addRequestProperty("Content-Length", String.valueOf(encodedData.length));
            connection.addRequestProperty("Content-Type", "application/json");
            connection.addRequestProperty("Content-Encoding", "gzip");
            connection.addRequestProperty("Connection", "close");
            connection.addRequestProperty("User-Agent", "plugin-statistics/v1");

            connection.setDoInput(true);
            connection.setDoOutput(true);

            int responseCode;

            try {
                connection.connect();

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(encodedData);
                }

                responseCode = connection.getResponseCode();
            } catch (IOException ex) {
                if (debug) {
                    plugin.getLogger().log(Level.WARNING, "[statistics] Failed to connect to service.", ex);
                }
                return;
            }

            if (debug && responseCode != 200) {
                plugin.getLogger().log(Level.WARNING, "[statistics] Service returned non-OK response code: {0}", responseCode);
                try (StringWriter writer = new StringWriter()) {
                    JsonSerialization.writeJsonObject(writer, dataMap);
                    plugin.getLogger().log(Level.INFO, "[statistics] POST data which caused this error: {0}", writer.toString());
                } catch (JsonSerialization.JsonException | IOException ex) {
                    plugin.getLogger().log(Level.WARNING, "[statistics] Failed to pretty-print data (to show the POST request which caused the error).", ex);
                }
            }
        }
    }

    /**
     * Class allowing for serializing Java Maps/Lists as json objects/arrays to a writer.
     * <p>
     * This is part of https://github.com/daboross/json-serialization, used with permission.
     * <p>
     * The only difference between the original class and this class is that indent/pretty-printing support has been
     * removed.
     *
     * @author daboross@daboross.net (David Ross)
     */
    public static class JsonSerialization {

        /**
         * Writes a number to the given writer in a format valid for json.
         *
         * @param writer The writer to write to.
         * @param number The number to write.
         * @throws JsonException If number is not finite.
         * @throws IOException   If the writer throws an IOException.
         */
        public static void writeNumber(Writer writer, Number number) throws JsonException, IOException {
            if (Double.isNaN(number.doubleValue()) || Double.isInfinite(number.doubleValue())) {
                throw new JsonException("Expected finite number, found `" + number + "`");
            }
            writer.write(number.toString());
        }

        /**
         * Writes a string to the given writer as an escaped json string.
         *
         * @param writer The writer to write to.
         * @param string The string to write.
         * @throws IOException If the writer throws an IOException.
         */
        public static void writeString(Writer writer, String string) throws IOException {
            writer.write('\"');
            char previousChar = 0;
            for (char c : string.toCharArray()) {
                switch (c) {
                    case '\\':
                    case '\"':
                        writer.write('\\');
                        writer.write(c);
                        break;
                    case '/':
                        if (previousChar == '<') {
                            writer.write('\\');
                        }
                        writer.write(c);
                        break;
                    case '\b':
                        writer.write("\\b");
                        break;
                    case '\t':
                        writer.write("\\t");
                        break;
                    case '\n':
                        writer.write("\\n");
                        break;
                    case '\f':
                        writer.write("\\f");
                        break;
                    case '\r':
                        writer.write("\\r");
                        break;
                    default:
                        if (c < ' ' || (c >= '\u0080' && c < '\u00a0')
                                || (c >= '\u2000' && c < '\u2100')) {
                            writer.write("\\u");
                            String hexStringTemp = Integer.toHexString(c);
                            writer.write("0000", 0, 4 - hexStringTemp.length());
                            writer.write(hexStringTemp);
                        } else {
                            writer.write(c);
                        }
                }
                previousChar = c;
            }
            writer.write('\"');
        }

        /**
         * Writes a json object string from the given Map to the given writer. Please note that this assumes that no
         * data structures are cyclical, and that all iterables are finite.
         *
         * @param writer The writer to write to.
         * @param values The value map. All keys will be proccessed with String.valueOf(), and values will be formatted
         *               depending on type. Of values taken from the map: All Iterables will be treated as json arrays,
         *               and all maps will be formatted as json maps.
         * @throws JsonException If a value of an unknown type is found.
         * @throws IOException   If the writer throws an IOException.
         */
        public static Writer writeJsonObject(Writer writer, Map<?, ?> values) throws JsonException, IOException {
            boolean commanate = false;
            final int length = values.size();
            Iterator<? extends Map.Entry<?, ?>> entries = values.entrySet().iterator();
            writer.write('{');

            if (length == 1) {
                Map.Entry entry = entries.next();
                writeString(writer, String.valueOf(entry.getKey()));
                writer.write(':');
                writeJsonValue(writer, entry.getValue());
            } else if (length != 0) {
                while (entries.hasNext()) {
                    Map.Entry entry = entries.next();
                    if (commanate) {
                        writer.write(',');
                    }
                    writeString(writer, String.valueOf(entry.getKey()));
                    writer.write(':');
                    writeJsonValue(writer, entry.getValue());
                    commanate = true;
                }
            }
            writer.write('}');
            return writer;
        }

        /**
         * Writes a json array string from the given Iterable to the given writer.　Please note that this assumes that no
         * data structures are cyclical, and that all iterables are finite.
         *
         * @param writer   The writer to write to.
         * @param iterable The iterable to produce values to put in the json array. Of values taken from the iterable:
         *                 All Iterables will be formatted as json arrays, all Maps will be formatted as json maps. A
         *                 JsonException is thrown if a value which is neither Iterable, Map, Number, String, Boolean
         *                 nor null is found.
         * @throws JsonException If a value of an unknown type is found.
         * @throws IOException   If the underlying writer throws an IOException.
         */
        public static Writer writeJsonArray(Writer writer, Iterable<?> iterable) throws JsonException, IOException {
            boolean alreadyDeclaredValue = false;
            writer.write('[');

            for (Object obj : iterable) {
                if (alreadyDeclaredValue) {
                    writer.write(',');
                }
                writeJsonValue(writer, obj);
                alreadyDeclaredValue = true;
            }

            writer.write(']');
            return writer;
        }

        /**
         * Writes a json array string from the given object to the given writer. Please note that this assumes that no
         * data structures are cyclical, and that all iterables are finite.
         *
         * @param writer The writer to write to.
         * @param value  The value to format. All Iterables will be formatted as json arrays, all Maps will be formatted
         *               as json maps. A JsonException is thrown if value is neither Iterable, Map, Number, String,
         *               Boolean nor null is found.
         * @throws JsonException If value is of an unknown type, or a Map or Iterable value produces a value of an
         *                       unkonwn type.
         * @throws IOException   If the underlying writer throws an IOException.
         */
        public static Writer writeJsonValue(Writer writer, Object value) throws JsonException, IOException {
            if (value == null) {
                writer.write("null");
            } else if (value instanceof Map) {
                writeJsonObject(writer, (Map) value);
            } else if (value instanceof Iterable) {
                writeJsonArray(writer, (Iterable) value);
            } else if (value instanceof Number) {
                writeNumber(writer, (Number) value);
            } else if (value instanceof Boolean) {
                writer.write(value.toString());
            } else if (value instanceof String) {
                writeString(writer, value.toString());
            } else {
                throw new JsonException("Invalid value: expected null, Map, Iterable, Number, Boolean or String, found `" + value.toString() + "` (`" + value.getClass() + "`)");
            }
            return writer;
        }

        public static class JsonException extends Exception {

            public JsonException(String message) {
                super(message);
            }
        }
    }
}
