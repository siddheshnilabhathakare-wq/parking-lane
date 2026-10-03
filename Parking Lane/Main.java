import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class Main {

    // ==========================================
    // DATABASE SETTINGS (from environment variables)
    //   DB_HOST, DB_NAME, DB_USER, DB_PASS   (required)
    //   DB_PORT                              (optional, default 3306)
    // ==========================================

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            if (fallback == null) {
                throw new IllegalStateException("Missing environment variable: " + key);
            }
            return fallback;
        }
        return v.trim();
    }

    public static void main(String[] args) {

        System.out.println("=================================");
        System.out.println("     SMART PARKING SYSTEM");
        System.out.println("=================================");
        System.out.println();

        try {

            String dbName = env("DB_NAME", null);
            String url = "jdbc:mysql://" + env("DB_HOST", null) + ":" + env("DB_PORT", "3306")
                    + "/" + dbName
                    + "?useSSL=true&requireSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true";

            // Connect to MySQL
            Connection connection =
                    DriverManager.getConnection(
                            url,
                            env("DB_USER", null),
                            env("DB_PASS", null)
                    );

            System.out.println("MySQL Connection Successful!");
            System.out.println("Database: " + dbName);
            System.out.println();

            // Display parking slots
            showParkingSlots(connection);

            // Close connection
            connection.close();

        } catch (Exception e) {

            System.out.println("=================================");
            System.out.println("       DATABASE ERROR");
            System.out.println("=================================");

            e.printStackTrace();
        }
    }


    // Method to display parking slots
    public static void showParkingSlots(Connection connection)
            throws Exception {

        String sql =
                "SELECT slot_number, slot_type, status " +
                "FROM park_slots " +
                "ORDER BY slot_id";

        PreparedStatement statement =
                connection.prepareStatement(sql);

        ResultSet result =
                statement.executeQuery();

        System.out.println("PARKING SLOT STATUS");
        System.out.println("---------------------------------");

        int count = 0;

        while (result.next()) {

            String slotNumber =
                    result.getString("slot_number");

            String slotType =
                    result.getString("slot_type");

            String status =
                    result.getString("status");

            System.out.println(
                    slotNumber
                    + " | "
                    + slotType
                    + " | "
                    + status
            );

            count++;
        }

        System.out.println("---------------------------------");
        System.out.println("Total slots found: " + count);

        result.close();
        statement.close();
    }
}
