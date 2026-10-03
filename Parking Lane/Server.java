import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Smart Parking server.
 *
 * DATABASE (chosen automatically):
 *   - No settings        -> local SQLite file "parking.db" (zero setup)
 *   - DB_HOST is set     -> online MySQL, using
 *                           DB_HOST, DB_NAME, DB_USER, DB_PASS
 *                           (optional: DB_PORT=3306, DB_SSL=true)
 *   Optional: DB_PATH = where the SQLite file lives (default parking.db)
 *
 * ALLOWED_ORIGIN: the website address allowed to call this server
 *       (e.g. https://my-dmart.netlify.app). Default "*" = anyone.
 *
 * PORT: read from the PORT environment variable (hosting sites set it),
 *       default 8080.
 */
public class Server {

    // ==========================================
    // SETTINGS
    // ==========================================

    private static String env(String key) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }

    private static final boolean USE_MYSQL = env("DB_HOST") != null;

    // Different names for MySQL so they never clash with older tables
    private static final String USERS = USE_MYSQL ? "parking_users" : "park_users";
    private static final String SLOTS = USE_MYSQL ? "parking_slots" : "park_slots";

    private static final int POOL_SIZE = USE_MYSQL ? 3 : 4;
    private static final long BOOKING_MILLIS = 2L * 60 * 60 * 1000;   // 2 hours
    private static final long SESSION_MILLIS = 12L * 60 * 60 * 1000;  // 12 hours

    private static final SecureRandom RNG = new SecureRandom();

    // ==========================================
    // MAIN
    // ==========================================

    public static void main(String[] args) throws Exception {

        initDatabase();

        int port = 8080;
        if (env("PORT") != null) port = Integer.parseInt(env("PORT"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/slots",     safe(Server::handleSlots));
        server.createContext("/api/mybooking", safe(Server::handleMyBooking));
        server.createContext("/api/register",  safe(Server::handleRegister));
        server.createContext("/api/login",     safe(Server::handleLogin));
        server.createContext("/api/book",      safe(Server::handleBook));
        server.createContext("/api/cancel",    safe(Server::handleCancel));
        server.createContext("/healthz",       safe(Server::handleHealth));
        server.createContext("/",              safe(Server::handleStatic));

        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println("=================================");
        System.out.println("  SMART PARKING SERVER RUNNING");
        System.out.println("  Port:     " + port);
        System.out.println("  Database: " + (USE_MYSQL ? "MySQL (online)" : "SQLite file: " + (env("DB_PATH") != null ? env("DB_PATH") : "parking.db")));
        System.out.println("  Open:     http://localhost:" + port);
        System.out.println("=================================");
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    /** Makes sure every request always gets an answer, even on unexpected errors. */
    private static HttpHandler safe(Handler inner) {
        return exchange -> {
            try {
                // CORS: lets the website on Netlify call this server
                String origin = env("ALLOWED_ORIGIN") != null ? env("ALLOWED_ORIGIN") : "*";
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
                exchange.getResponseHeaders().add("Access-Control-Max-Age", "86400");
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                inner.handle(exchange);
            } catch (Throwable t) {
                t.printStackTrace();
                try {
                    sendJson(exchange, 500, "{\"success\":false,\"message\":\"Server error. Please try again.\"}");
                } catch (Throwable ignored) { }
            } finally {
                exchange.close();
            }
        };
    }

    // ==========================================
    // DATABASE: connection + tiny connection pool
    // ==========================================

    private static Connection openConnection() throws SQLException {

        if (USE_MYSQL) {
            String name = env("DB_NAME"), user = env("DB_USER"), pass = env("DB_PASS");
            if (name == null || user == null || pass == null) {
                throw new SQLException("DB_NAME, DB_USER and DB_PASS must be set when DB_HOST is used.");
            }
            String port = env("DB_PORT") != null ? env("DB_PORT") : "3306";
            boolean ssl = !"false".equalsIgnoreCase(env("DB_SSL"));

            String url = "jdbc:mysql://" + env("DB_HOST") + ":" + port + "/" + name
                    + "?useSSL=" + ssl + (ssl ? "&requireSSL=true" : "")
                    + "&serverTimezone=UTC&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=10000&socketTimeout=30000";
            return DriverManager.getConnection(url, user, pass);
        }

        String path = env("DB_PATH") != null ? env("DB_PATH") : "parking.db";
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");   // wait if another request is writing
        }
        return c;
    }

    private static final BlockingQueue<Connection> IDLE = new LinkedBlockingQueue<>();
    private static final Semaphore PERMITS = new Semaphore(POOL_SIZE, true);

    private static boolean usable(Connection c) {
        try {
            return !c.isClosed() && c.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private static void closeQuietly(Connection c) {
        try { c.close(); } catch (Exception ignored) { }
    }

    /**
     * Borrows a connection from the pool. Calling close() on it (as the
     * try-with-resources blocks do) hands it back instead of closing it.
     * This keeps us under the connection limit of free cloud databases.
     */
    private static Connection getConnection() throws SQLException {

        try {
            if (!PERMITS.tryAcquire(15, TimeUnit.SECONDS)) {
                throw new SQLException("Database is busy, please try again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for the database.");
        }

        Connection real;
        try {
            while ((real = IDLE.poll()) != null) {
                if (usable(real)) break;
                closeQuietly(real);
            }
            if (real == null) real = openConnection();
        } catch (SQLException | RuntimeException e) {
            PERMITS.release();
            throw e;
        }

        final Connection conn = real;
        final boolean[] released = {false};

        return (Connection) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        synchronized (released) {
                            if (!released[0]) {
                                released[0] = true;
                                try {
                                    if (!conn.getAutoCommit()) {
                                        conn.rollback();
                                        conn.setAutoCommit(true);
                                    }
                                } catch (SQLException ignored) { }
                                IDLE.offer(conn);
                                PERMITS.release();
                            }
                        }
                        return null;
                    }
                    try {
                        return method.invoke(conn, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Creates the tables (and the 60 slots) the first time the server starts. */
    private static void initDatabase() throws Exception {

        try {
            Class.forName(USE_MYSQL ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Database driver not found. Put the "
                    + (USE_MYSQL ? "MySQL connector" : "sqlite-jdbc")
                    + " .jar file inside the lib folder.", e);
        }

        String autoId = USE_MYSQL ? "INT AUTO_INCREMENT PRIMARY KEY"
                                  : "INTEGER PRIMARY KEY AUTOINCREMENT";

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + USERS + " ("
                    + "user_id " + autoId + ", "
                    + "name VARCHAR(100) NOT NULL, "
                    + "email VARCHAR(100) NOT NULL UNIQUE, "
                    + "password_hash VARCHAR(255) NOT NULL, "
                    + "created_at BIGINT NOT NULL)");

            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + SLOTS + " ("
                    + "slot_id " + autoId + ", "
                    + "slot_number VARCHAR(20) NOT NULL UNIQUE, "
                    + "slot_type VARCHAR(20) NOT NULL, "
                    + "status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', "
                    + "user_email VARCHAR(100), "
                    + "user_name VARCHAR(100), "
                    + "vehicle_number VARCHAR(20), "
                    + "vehicle_type VARCHAR(20), "
                    + "disability_type VARCHAR(50), "
                    + "expiry_time BIGINT)");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + SLOTS)) {
                rs.next();
                if (rs.getInt(1) == 0) {
                    seedSlots(conn, "C", "CAR", 20);
                    seedSlots(conn, "B", "BIKE", 30);
                    seedSlots(conn, "A", "ACCESSIBLE", 10);
                    System.out.println("Created 60 parking slots.");
                }
            }
        }
    }

    private static void seedSlots(Connection conn, String prefix, String type, int count) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + SLOTS + " (slot_number, slot_type) VALUES (?, ?)")) {
            for (int i = 1; i <= count; i++) {
                ps.setString(1, prefix + i);
                ps.setString(2, type);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void expireOldBookings(Connection conn) throws SQLException {
        String sql = "UPDATE " + SLOTS + " SET status='AVAILABLE', user_email=NULL, "
                + "user_name=NULL, vehicle_number=NULL, vehicle_type=NULL, disability_type=NULL, "
                + "expiry_time=NULL WHERE status='RESERVED' AND expiry_time < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // ==========================================
    // STATIC FILES (only these are public)
    // ==========================================

    private static final Map<String, String> PUBLIC_FILES = Map.of(
            "/index.html", "text/html; charset=UTF-8",
            "/style.css", "text/css; charset=UTF-8",
            "/script.js", "application/javascript; charset=UTF-8"
    );

    private static void handleHealth(HttpExchange exchange) throws IOException {
        byte[] ok = "ok".getBytes("UTF-8");
        exchange.sendResponseHeaders(200, ok.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(ok);
        }
    }

    private static void handleStatic(HttpExchange exchange) throws IOException {

        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        String contentType = PUBLIC_FILES.get(path);

        // Images folder: only simple file names with image extensions
        if (contentType == null && path.matches("^/images/[A-Za-z0-9_-]+\\.(jpg|jpeg|png|webp)$")) {
            contentType = path.endsWith(".png") ? "image/png"
                        : path.endsWith(".webp") ? "image/webp"
                        : "image/jpeg";
        }

        File file = new File("." + path);

        if (contentType == null || !file.isFile()) {
            byte[] resp = "404 Not Found".getBytes("UTF-8");
            exchange.sendResponseHeaders(404, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            return;
        }

        byte[] bytes = Files.readAllBytes(file.toPath());
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ==========================================
    // REQUEST / JSON HELPERS
    // ==========================================

    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream is = exchange.getRequestBody();
        byte[] buf = new byte[1024];
        int n;
        while ((n = is.read(buf)) != -1 && bos.size() < 20000) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    /** Reads a string value from a flat JSON object (handles \" and \\ escapes). */
    private static String jsonGet(String json, String key) {
        if (json == null) return null;
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*").matcher(json);
        if (!m.find()) return null;

        int i = m.end();
        if (i >= json.length() || json.charAt(i) != '"') return null;   // null / number / missing
        i++;

        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\' && i < json.length()) {
                char n = json.charAt(i++);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            } catch (NumberFormatException ignored) { }
                            i += 4;
                        }
                        break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes("UTF-8");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void fail(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"success\":false,\"message\":\"" + esc(message) + "\"}");
    }

    private static boolean requirePost(HttpExchange exchange) throws IOException {
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) return true;
        fail(exchange, 405, "Method not allowed.");
        return false;
    }

    // ==========================================
    // PASSWORDS (salted PBKDF2) + SESSIONS
    // ==========================================

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private static String hashPassword(String password) throws Exception {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return "pbkdf2$100000$" + hex(salt) + "$" + hex(pbkdf2(password, salt, 100000));
    }

    private static boolean verifyPassword(String password, String stored) throws Exception {
        if (stored.startsWith("pbkdf2$")) {
            String[] p = stored.split("\\$");
            byte[] actual = pbkdf2(password, unhex(p[2]), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(unhex(p[3]), actual);
        }
        // Older accounts used a plain SHA-256 hash
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(password.getBytes("UTF-8"));
        return MessageDigest.isEqual(hex(sha).getBytes("UTF-8"), stored.getBytes("UTF-8"));
    }

    private static final class Session {
        final String email, name;
        final long expires;
        Session(String email, String name, long expires) {
            this.email = email;
            this.name = name;
            this.expires = expires;
        }
    }

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private static String newSession(String email, String name) {
        long now = System.currentTimeMillis();
        SESSIONS.values().removeIf(s -> s.expires < now);
        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = hex(raw);
        SESSIONS.put(token, new Session(email, name, now + SESSION_MILLIS));
        return token;
    }

    /** Returns the logged-in user, or sends a 401 and returns null. */
    private static Session requireAuth(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Session s = SESSIONS.get(header.substring(7).trim());
            if (s != null && s.expires > System.currentTimeMillis()) return s;
        }
        sendJson(exchange, 401, "{\"success\":false,\"exists\":false,\"message\":\"Please login again.\"}");
        return null;
    }

    // Simple brute-force protection: 8 failed logins per email per 10 minutes
    private static final Map<String, long[]> FAILED_LOGINS = new ConcurrentHashMap<>();

    private static boolean tooManyFailures(String email) {
        long[] f = FAILED_LOGINS.get(email);
        if (f == null) return false;
        if (System.currentTimeMillis() - f[1] > 10 * 60 * 1000) {
            FAILED_LOGINS.remove(email);
            return false;
        }
        return f[0] >= 8;
    }

    private static void recordFailure(String email) {
        FAILED_LOGINS.merge(email, new long[]{1, System.currentTimeMillis()},
                (old, fresh) -> new long[]{old[0] + 1, old[1]});
    }

    // ==========================================
    // VALIDATION
    // ==========================================

    private static final Pattern EMAIL_RE = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern VEHICLE_RE = Pattern.compile("^[A-Z0-9 -]{4,15}$");
    private static final Pattern SLOT_RE = Pattern.compile("^[CBA][0-9]{1,3}$");
    private static final java.util.List<String> DISABILITIES = java.util.Arrays.asList(
            "Blindness", "Physical Handicap", "Wheelchair User", "Mobility Impairment", "Other");

    // ==========================================
    // GET /api/slots
    // ==========================================

    private static void handleSlots(HttpExchange exchange) throws Exception {
        try (Connection conn = getConnection()) {
            expireOldBookings(conn);

            StringBuilder car = new StringBuilder();
            StringBuilder bike = new StringBuilder();
            StringBuilder acc = new StringBuilder();

            String sql = "SELECT slot_number, slot_type, status FROM " + SLOTS + " ORDER BY slot_id";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String type = rs.getString("slot_type");
                    String entry = "{\"slotNumber\":\"" + esc(rs.getString("slot_number"))
                            + "\",\"status\":\"" + esc(rs.getString("status")) + "\"},";

                    if (type.equals("CAR")) car.append(entry);
                    else if (type.equals("BIKE")) bike.append(entry);
                    else acc.append(entry);
                }
            }

            sendJson(exchange, 200, "{\"CAR\":[" + trimComma(car) + "],\"BIKE\":[" + trimComma(bike)
                    + "],\"ACCESSIBLE\":[" + trimComma(acc) + "]}");

        } catch (SQLException e) {
            e.printStackTrace();
            fail(exchange, 500, "Could not reach the database.");
        }
    }

    private static String trimComma(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    // ==========================================
    // GET /api/mybooking   (needs login)
    // ==========================================

    private static void handleMyBooking(HttpExchange exchange) throws Exception {
        Session user = requireAuth(exchange);
        if (user == null) return;

        try (Connection conn = getConnection()) {
            expireOldBookings(conn);

            String sql = "SELECT slot_number, slot_type, vehicle_number, vehicle_type, "
                    + "disability_type, expiry_time FROM " + SLOTS
                    + " WHERE user_email=? AND status='RESERVED'";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, user.email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        sendJson(exchange, 200, "{\"exists\":false}");
                        return;
                    }

                    String disability = rs.getString("disability_type");
                    long expiry = rs.getLong("expiry_time");

                    sendJson(exchange, 200, "{\"exists\":true,"
                            + "\"slotNumber\":\"" + esc(rs.getString("slot_number")) + "\","
                            + "\"slotType\":\"" + esc(rs.getString("slot_type")) + "\","
                            + "\"vehicleNumber\":\"" + esc(rs.getString("vehicle_number")) + "\","
                            + "\"vehicleType\":\"" + esc(rs.getString("vehicle_type")) + "\","
                            + "\"disabilityType\":\"" + esc(disability) + "\","
                            + "\"expiryTime\":\"" + (expiry > 0 ? java.time.Instant.ofEpochMilli(expiry).toString() : "") + "\"}");
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500, "{\"exists\":false,\"message\":\"Could not reach the database.\"}");
        }
    }

    // ==========================================
    // POST /api/register
    // ==========================================

    private static void handleRegister(HttpExchange exchange) throws Exception {
        if (!requirePost(exchange)) return;

        String body = readBody(exchange);
        String name = jsonGet(body, "name");
        String email = jsonGet(body, "email");
        String password = jsonGet(body, "password");

        if (name == null || email == null || password == null
                || name.trim().isEmpty() || email.trim().isEmpty() || password.isEmpty()) {
            fail(exchange, 400, "Please fill all fields.");
            return;
        }

        name = name.trim();
        email = email.trim().toLowerCase();

        if (name.length() > 100) { fail(exchange, 400, "Name is too long."); return; }
        if (email.length() > 100 || !EMAIL_RE.matcher(email).matches()) {
            fail(exchange, 400, "Please enter a valid email address.");
            return;
        }
        if (password.length() < 6 || password.length() > 100) {
            fail(exchange, 400, "Password must be 6 to 100 characters.");
            return;
        }

        try (Connection conn = getConnection()) {

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id FROM " + USERS + " WHERE LOWER(email)=?")) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        fail(exchange, 409, "An account with this email already exists.");
                        return;
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + USERS + " (name, email, password_hash, created_at) VALUES (?,?,?,?)")) {
                ps.setString(1, name);
                ps.setString(2, email);
                ps.setString(3, hashPassword(password));
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }

            sendJson(exchange, 200, "{\"success\":true}");

        } catch (SQLException e) {
            e.printStackTrace();
            fail(exchange, 500, "Could not create the account. Please try again.");
        }
    }

    // ==========================================
    // POST /api/login
    // ==========================================

    private static void handleLogin(HttpExchange exchange) throws Exception {
        if (!requirePost(exchange)) return;

        String body = readBody(exchange);
        String email = jsonGet(body, "email");
        String password = jsonGet(body, "password");

        if (email == null || password == null) {
            fail(exchange, 400, "Please enter your email and password.");
            return;
        }
        email = email.trim().toLowerCase();

        if (tooManyFailures(email)) {
            fail(exchange, 429, "Too many failed attempts. Please wait a few minutes and try again.");
            return;
        }

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name, email, password_hash FROM " + USERS + " WHERE LOWER(email)=?")) {

            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {

                if (!rs.next() || !verifyPassword(password, rs.getString("password_hash"))) {
                    recordFailure(email);
                    fail(exchange, 401, "Invalid email or password.");
                    return;
                }

                FAILED_LOGINS.remove(email);
                String name = rs.getString("name");
                String storedEmail = rs.getString("email");
                String token = newSession(storedEmail, name);

                sendJson(exchange, 200, "{\"success\":true,\"name\":\"" + esc(name)
                        + "\",\"email\":\"" + esc(storedEmail) + "\",\"token\":\"" + token + "\"}");
            }

        } catch (SQLException e) {
            e.printStackTrace();
            fail(exchange, 500, "Could not log in. Please try again.");
        }
    }

    // ==========================================
    // POST /api/book   (needs login)
    // ==========================================

    private static void handleBook(HttpExchange exchange) throws Exception {
        if (!requirePost(exchange)) return;
        Session user = requireAuth(exchange);
        if (user == null) return;

        String body = readBody(exchange);
        String slotNumber = jsonGet(body, "slotNumber");
        String vehicleNumber = jsonGet(body, "vehicleNumber");
        String vehicleType = jsonGet(body, "vehicleType");
        String disabilityType = jsonGet(body, "disabilityType");

        if (slotNumber == null || !SLOT_RE.matcher(slotNumber).matches()) {
            fail(exchange, 400, "Invalid slot.");
            return;
        }
        if (vehicleNumber == null) {
            fail(exchange, 400, "Please enter your vehicle number.");
            return;
        }
        vehicleNumber = vehicleNumber.trim().toUpperCase();
        if (!VEHICLE_RE.matcher(vehicleNumber).matches()) {
            fail(exchange, 400, "Please enter a valid vehicle number (letters and numbers only).");
            return;
        }
        if (vehicleType == null
                || !(vehicleType.equals("CAR") || vehicleType.equals("BIKE") || vehicleType.equals("SCOOTY"))) {
            fail(exchange, 400, "Please select a vehicle type.");
            return;
        }

        char kind = slotNumber.charAt(0);
        if (kind == 'C' && !vehicleType.equals("CAR")) {
            fail(exchange, 400, "This slot is only for cars.");
            return;
        }
        if (kind == 'B' && vehicleType.equals("CAR")) {
            fail(exchange, 400, "This slot is only for bikes and scooties.");
            return;
        }
        if (kind == 'A') {
            if (disabilityType == null || !DISABILITIES.contains(disabilityType)) {
                fail(exchange, 400, "Please select your disability type.");
                return;
            }
        } else {
            disabilityType = null;
        }

        try (Connection conn = getConnection()) {
            expireOldBookings(conn);

            // One active booking per person (the page shows a single booking)
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM " + SLOTS + " WHERE user_email=? AND status='RESERVED'")) {
                ps.setString(1, user.email);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        fail(exchange, 409, "You already have an active booking. Cancel it first to book another slot.");
                        return;
                    }
                }
            }

            // Only succeeds if the slot is still AVAILABLE (no double booking)
            int updated;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + SLOTS + " SET status='RESERVED', user_email=?, user_name=?, "
                    + "vehicle_number=?, vehicle_type=?, disability_type=?, expiry_time=? "
                    + "WHERE slot_number=? AND status='AVAILABLE'")) {
                ps.setString(1, user.email);
                ps.setString(2, user.name);
                ps.setString(3, vehicleNumber);
                ps.setString(4, vehicleType);
                ps.setString(5, disabilityType);
                ps.setLong(6, System.currentTimeMillis() + BOOKING_MILLIS);
                ps.setString(7, slotNumber);
                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                fail(exchange, 409, "Sorry! This slot is already reserved.");
                return;
            }

            sendJson(exchange, 200, "{\"success\":true}");

        } catch (SQLException e) {
            e.printStackTrace();
            fail(exchange, 500, "Could not book this slot. Please try again.");
        }
    }

    // ==========================================
    // POST /api/cancel   (needs login)
    // ==========================================

    private static void handleCancel(HttpExchange exchange) throws Exception {
        if (!requirePost(exchange)) return;
        Session user = requireAuth(exchange);
        if (user == null) return;

        String slotNumber = jsonGet(readBody(exchange), "slotNumber");
        if (slotNumber == null || !SLOT_RE.matcher(slotNumber).matches()) {
            fail(exchange, 400, "Invalid slot.");
            return;
        }

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE " + SLOTS + " SET status='AVAILABLE', user_email=NULL, user_name=NULL, "
                     + "vehicle_number=NULL, vehicle_type=NULL, disability_type=NULL, expiry_time=NULL "
                     + "WHERE slot_number=? AND user_email=? AND status='RESERVED'")) {

            ps.setString(1, slotNumber);
            ps.setString(2, user.email);

            if (ps.executeUpdate() == 0) {
                fail(exchange, 404, "No active booking found for this slot.");
                return;
            }

            sendJson(exchange, 200, "{\"success\":true}");

        } catch (SQLException e) {
            e.printStackTrace();
            fail(exchange, 500, "Could not cancel the booking. Please try again.");
        }
    }
}
