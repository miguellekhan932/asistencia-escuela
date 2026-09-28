package database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class DatabaseConnection {

    private static final String URL = "jdbc:sqlite:asistencia.db";

    public static Connection conectar() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    public static void inicializarTablas() {
        try (Connection conn = conectar()) {
            boolean migrarHorariosViejos = tablaExiste(conn, "horarios")
                    && !columnaExiste(conn, "horarios", "tipo_horario");

            if (migrarHorariosViejos) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE horarios RENAME TO horarios_vieja");
                } catch (SQLException e) {
                    System.err.println("[ERROR SQLITE]: No se pudo renombrar la tabla horarios: " + e.getMessage());
                    return;
                }
            }

            crearEstructura(conn);

            if (migrarHorariosViejos) {
                migrarHorariosViejos(conn);
            }

            migrarColumnasPersonas(conn);
            sembrarConfiguracion(conn);

            System.out.println("[SQLITE]: Base de datos e infraestructura de tablas verificadas correctamente.");
        } catch (SQLException e) {
            System.err.println("[ERROR SQLITE]: No se pudo inicializar las tablas: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void crearEstructura(Connection conn) throws SQLException {
        String tablaHorarios = "CREATE TABLE IF NOT EXISTS horarios ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "nombre_horario TEXT NOT NULL UNIQUE, "
                + "tipo_horario TEXT NOT NULL DEFAULT 'SECCION', "
                + "margen_tolerancia_min INTEGER DEFAULT 15"
                + ");";

        String tablaHorarioSemanal = "CREATE TABLE IF NOT EXISTS horario_semanal ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "horario_id INTEGER NOT NULL, "
                + "dia INTEGER NOT NULL CHECK (dia BETWEEN 1 AND 7), "
                + "hora_entrada TEXT NOT NULL, "
                + "hora_salida TEXT NOT NULL, "
                + "UNIQUE (horario_id, dia), "
                + "FOREIGN KEY (horario_id) REFERENCES horarios(id) ON DELETE CASCADE"
                + ");";

        String tablaPersonas = "CREATE TABLE IF NOT EXISTS personas ("
                + "codigo_rfid TEXT PRIMARY KEY, "
                + "cedula TEXT UNIQUE NOT NULL, "
                + "nombre_completo TEXT NOT NULL, "
                + "rol TEXT NOT NULL, "
                + "ano_seccion TEXT, "
                + "telefono TEXT DEFAULT '', "
                + "horario_id INTEGER, "
                + "FOREIGN KEY (horario_id) REFERENCES horarios(id)"
                + ");";

        String tablaContactos = "CREATE TABLE IF NOT EXISTS contactos_estudiante ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "codigo_rfid_estudiante TEXT NOT NULL, "
                + "nombre_completo TEXT NOT NULL, "
                + "parentesco TEXT NOT NULL, "
                + "cedula TEXT, "
                + "telefono TEXT NOT NULL, "
                + "es_principal INTEGER DEFAULT 0, "
                + "FOREIGN KEY(codigo_rfid_estudiante) REFERENCES personas(codigo_rfid) ON DELETE CASCADE"
                + ");";

        String tablaPermisos = "CREATE TABLE IF NOT EXISTS permisos_salida ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "codigo_rfid_estudiante TEXT NOT NULL, "
                + "fecha_emision TEXT NOT NULL, "
                + "motivo TEXT NOT NULL, "
                + "autorizado_por TEXT NOT NULL, "
                + "estado TEXT DEFAULT 'PENDIENTE', "
                + "FOREIGN KEY(codigo_rfid_estudiante) REFERENCES personas(codigo_rfid)"
                + ");";

        String tablaAsistencias = "CREATE TABLE IF NOT EXISTS asistencias ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "codigo_rfid TEXT NOT NULL, "
                + "fecha_hora TEXT NOT NULL, "
                + "tipo_evento TEXT NOT NULL, "
                + "evaluacion TEXT NOT NULL, "
                + "FOREIGN KEY(codigo_rfid) REFERENCES personas(codigo_rfid)"
                + ");";

        String tablaPasesSalida = "CREATE TABLE IF NOT EXISTS pases_salida ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "asistencias_id INTEGER NOT NULL, "
                + "codigo_rfid TEXT NOT NULL, "
                + "tipo_pase TEXT NOT NULL, "
                + "motivo TEXT NOT NULL, "
                + "autorizado_por TEXT DEFAULT '', "
                + "fecha_hora TEXT NOT NULL, "
                + "FOREIGN KEY(asistencias_id) REFERENCES asistencias(id) ON DELETE CASCADE"
                + ");";

        String tablaConfiguracion = "CREATE TABLE IF NOT EXISTS configuracion ("
                + "clave TEXT PRIMARY KEY, "
                + "valor TEXT DEFAULT ''"
                + ");";

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute(tablaHorarios);
            stmt.execute(tablaHorarioSemanal);
            stmt.execute(tablaPersonas);
            stmt.execute(tablaContactos);
            stmt.execute(tablaPermisos);
            stmt.execute(tablaAsistencias);
            stmt.execute(tablaPasesSalida);
            stmt.execute(tablaConfiguracion);
        }
    }

    private static void migrarHorariosViejos(Connection conn) throws SQLException {
        Set<String> vistos = new HashSet<>();
        String sqlSel = "SELECT id, nombre_horario, hora_entrada, hora_salida, margen_tolerancia_min FROM horarios_vieja";
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sqlSel)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String nombre = rs.getString("nombre_horario");
                if (nombre == null || nombre.trim().isEmpty() || !vistos.add(nombre.trim()))
                    continue;
                int tolerancia = rs.getInt("margen_tolerancia_min");
                String entrada = rs.getString("hora_entrada");
                String salida = rs.getString("hora_salida");
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO horarios (id, nombre_horario, tipo_horario, margen_tolerancia_min) VALUES (?, ?, 'SECCION', ?)")) {
                    ins.setInt(1, id);
                    ins.setString(2, nombre.trim());
                    ins.setInt(3, tolerancia);
                    ins.executeUpdate();
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO horario_semanal (horario_id, dia, hora_entrada, hora_salida) VALUES (?, ?, ?, ?)")) {
                    for (int dia = 1; dia <= 7; dia++) {
                        ins.setInt(1, id);
                        ins.setInt(2, dia);
                        ins.setString(3, entrada);
                        ins.setString(4, salida);
                        ins.executeUpdate();
                    }
                }
            }
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE horarios_vieja");
        }
    }

    private static void migrarColumnasPersonas(Connection conn) throws SQLException {
        if (!columnaExiste(conn, "personas", "telefono")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE personas ADD COLUMN telefono TEXT DEFAULT ''");
            }
        }
        if (!columnaExiste(conn, "personas", "horario_id")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE personas ADD COLUMN horario_id INTEGER");
            }
        }
    }

    private static void sembrarConfiguracion(Connection conn) throws SQLException {
        String sql = "INSERT OR IGNORE INTO configuracion (clave, valor) VALUES (?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            insertarConfig(stmt, "GSM_MODO", "CONSOLA");
            insertarConfig(stmt, "GSM_PUERTO", "COM3");
            insertarConfig(stmt, "GSM_BAUD", "9600");
            insertarConfig(stmt, "NOMBRE_INSTITUCION", "Institución Educativa");
        }
    }

    private static void insertarConfig(PreparedStatement stmt, String clave, String valor) throws SQLException {
        stmt.setString(1, clave);
        stmt.setString(2, valor);
        stmt.executeUpdate();
    }

    public static String obtenerConfiguracion(String clave) {
        String sql = "SELECT valor FROM configuracion WHERE clave = ?";
        try (Connection conn = conectar();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, clave);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next())
                    return rs.getString(1);
            }
        } catch (SQLException e) {
            System.err.println("[ERROR SQLITE]: No se pudo leer configuración '" + clave + "': " + e.getMessage());
        }
        return "";
    }

    public static Map<String, String> obtenerTodasConfiguraciones() {
        Map<String, String> mapa = new HashMap<>();
        String sql = "SELECT clave, valor FROM configuracion ORDER BY clave";
        try (Connection conn = conectar();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                mapa.put(rs.getString("clave"), rs.getString("valor"));
            }
        } catch (SQLException e) {
            System.err.println("[ERROR SQLITE]: No se pudo leer la configuración: " + e.getMessage());
        }
        return mapa;
    }

    public static void guardarConfiguracion(String clave, String valor) {
        String sql = "INSERT INTO configuracion (clave, valor) VALUES (?, ?) "
                + "ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor";
        try (Connection conn = conectar();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, clave);
            stmt.setString(2, valor);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ERROR SQLITE]: No se pudo guardar configuración '" + clave + "': " + e.getMessage());
        }
    }

    private static boolean tablaExiste(Connection conn, String tabla) {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            stmt.setString(1, tabla);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean columnaExiste(Connection conn, String tabla, String columna) {
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tabla + ")")) {
            while (rs.next()) {
                if (columna.equalsIgnoreCase(rs.getString("name")))
                    return true;
            }
        } catch (SQLException e) {
            return false;
        }
        return false;
    }
}
