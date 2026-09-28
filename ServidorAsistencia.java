package service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import database.DatabaseConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ServidorAsistencia {

    private static final Map<String, LocalDateTime> ultimosMarcajes = new HashMap<>();
    private static final int TIEMPO_ESPERA_SEGUNDOS = 60;
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws IOException {
        DatabaseConnection.inicializarTablas();

        // Asignación dinámica de puerto para compatibilidad con Hosting Nube
        // (Render/Railway/Heroku)
        String puertoEnv = System.getenv("PORT");
        int puerto = (puertoEnv != null && !puertoEnv.isEmpty()) ? Integer.parseInt(puertoEnv) : 8080;

        HttpServer server = HttpServer.create(new InetSocketAddress(puerto), 0);

        // Rutas y Endpoints REST API
        server.createContext("/", new PaginaWebHandler());
        server.createContext("/api/fichas", new FichasHandler());
        server.createContext("/api/horarios", new HorariosHandler());
        server.createContext("/api/asistencia", new AsistenciaHandler());
        server.createContext("/api/permisos", new PermisosHandler());
        server.createContext("/api/historial-reciente", new HistorialRecienteHandler());
        server.createContext("/api/config", new ConfigHandler());
        server.createContext("/api/sincronizar", new SincronizacionHandler());

        server.setExecutor(null);
        System.out.println("Servidor Java Activo en Puerto " + puerto + " - Control Escolar e Híbrido");
        server.start();
    }

    // --- HANDLER PAGINA WEB ---
    static class PaginaWebHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                byte[] respuesta = Files.readAllBytes(Paths.get("index.html"));
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
                exchange.sendResponseHeaders(200, respuesta.length);
                OutputStream os = exchange.getResponseBody();
                os.write(respuesta);
                os.close();
            } catch (IOException e) {
                byte[] mensaje = "index.html no encontrado en la carpeta de trabajo.".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, mensaje.length);
                exchange.getResponseBody().write(mensaje);
                exchange.getResponseBody().close();
            }
        }
    }

    // --- HANDLER FICHAS / PERSONAS ---
    static class FichasHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String metodo = ex.getRequestMethod().toUpperCase();
                if (metodo.equals("OPTIONS")) {
                    opciones(ex);
                    return;
                }
                String recurso = rutaRelativa(ex, "/api/fichas");
                if (metodo.equals("GET")) {
                    if (recurso.isEmpty())
                        listarFichas(ex);
                    else
                        buscarFicha(ex, recurso);
                } else if (metodo.equals("POST")) {
                    if (recurso.isEmpty())
                        crearFicha(ex);
                    else
                        enviarJson(ex, 405, errorJson("Ruta inválida para POST."));
                } else if (metodo.equals("PUT")) {
                    actualizarFicha(ex, recurso);
                } else if (metodo.equals("DELETE")) {
                    eliminarFicha(ex, recurso);
                } else {
                    enviarJson(ex, 405, errorJson("Método no soportado."));
                }
            } catch (Exception e) {
                e.printStackTrace();
                enviarJson(ex, 500, errorJson(e.getMessage()));
            }
        }

        private void listarFichas(HttpExchange ex) throws Exception {
            StringBuilder json = new StringBuilder("[");
            String sql = "SELECT codigo_rfid, cedula, nombre_completo, rol, COALESCE(ano_seccion, '') AS ano_seccion, "
                    + "COALESCE(telefono, '') AS telefono, COALESCE(horario_id, 0) AS horario_id "
                    + "FROM personas WHERE eliminado = 0 ORDER BY nombre_completo ASC";
            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                boolean primero = true;
                while (rs.next()) {
                    if (!primero)
                        json.append(",");
                    json.append("{")
                            .append("\"cedula\":").append(json(rs.getString("cedula"))).append(",")
                            .append("\"codigoRfid\":").append(json(rs.getString("codigo_rfid"))).append(",")
                            .append("\"nombreCompleto\":").append(json(rs.getString("nombre_completo"))).append(",")
                            .append("\"rol\":").append(json(rs.getString("rol"))).append(",")
                            .append("\"anoSeccion\":").append(json(rs.getString("ano_seccion"))).append(",")
                            .append("\"telefono\":").append(json(rs.getString("telefono"))).append(",")
                            .append("\"horarioId\":").append(rs.getInt("horario_id"))
                            .append("}");
                    primero = false;
                }
            }
            json.append("]");
            enviarJson(ex, 200, json.toString());
        }

        private void buscarFicha(HttpExchange ex, String valor) throws Exception {
            String sql = "SELECT codigo_rfid, cedula, nombre_completo, rol, COALESCE(ano_seccion, '') AS ano_seccion, "
                    + "COALESCE(telefono, '') AS telefono, COALESCE(horario_id, 0) AS horario_id "
                    + "FROM personas WHERE (cedula = ? OR codigo_rfid = ?) AND eliminado = 0";
            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, valor);
                stmt.setString(2, valor);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String json = "{"
                                + "\"cedula\":" + json(rs.getString("cedula")) + ","
                                + "\"codigoRfid\":" + json(rs.getString("codigo_rfid")) + ","
                                + "\"nombreCompleto\":" + json(rs.getString("nombre_completo")) + ","
                                + "\"rol\":" + json(rs.getString("rol")) + ","
                                + "\"anoSeccion\":" + json(rs.getString("ano_seccion")) + ","
                                + "\"telefono\":" + json(rs.getString("telefono")) + ","
                                + "\"horarioId\":" + rs.getInt("horario_id")
                                + "}";
                        enviarJson(ex, 200, json);
                    } else {
                        enviarJson(ex, 404, errorJson("Cédula o código no registrado."));
                    }
                }
            }
        }

        private void crearFicha(HttpExchange ex) throws Exception {
            Map<String, String> body = leerBody(ex);
            String cedula = body.getOrDefault("cedula", "").trim();
            String nombre = body.getOrDefault("nombreCompleto", "").trim();
            String rol = body.getOrDefault("rol", "").trim();
            String anoSeccion = body.getOrDefault("anoSeccion", "").trim();
            String rfid = body.getOrDefault("codigoRfid", "").trim();
            String telefono = body.getOrDefault("telefono", "").trim();
            Integer horarioId = obtenerIdNullable(body.get("horarioId"));

            if (cedula.isEmpty() || nombre.isEmpty() || rol.isEmpty()) {
                enviarJson(ex, 400, errorJson("Cédula, nombre y rol son obligatorios."));
                return;
            }
            if (rfid.isEmpty())
                rfid = "CARNET-" + cedula;

            try (Connection conn = DatabaseConnection.conectar()) {
                String rfidExistente = null;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT codigo_rfid FROM personas WHERE cedula = ? OR codigo_rfid = ?")) {
                    stmt.setString(1, cedula);
                    stmt.setString(2, rfid);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next())
                            rfidExistente = rs.getString(1);
                    }
                }

                if (rfidExistente != null) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE personas SET codigo_rfid = ?, cedula = ?, nombre_completo = ?, rol = ?, "
                                    + "ano_seccion = ?, telefono = ?, horario_id = ?, sincronizado = 0, eliminado = 0 WHERE codigo_rfid = ?")) {
                        asignarCamposFicha(stmt, rfid, cedula, nombre, rol, anoSeccion, telefono, horarioId);
                        stmt.setString(8, rfidExistente);
                        stmt.executeUpdate();
                    }
                } else {
                    String uuid = UUID.randomUUID().toString();
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO personas (id, codigo_rfid, cedula, nombre_completo, rol, ano_seccion, telefono, horario_id, sincronizado, eliminado) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0)")) {
                        stmt.setString(1, uuid);
                        stmt.setString(2, rfid);
                        stmt.setString(3, cedula);
                        stmt.setString(4, nombre);
                        stmt.setString(5, rol);
                        stmt.setString(6, anoSeccion);
                        stmt.setString(7, telefono);
                        if (horarioId == null)
                            stmt.setNull(8, java.sql.Types.INTEGER);
                        else
                            stmt.setInt(8, horarioId);
                        stmt.executeUpdate();
                    }
                }
            }
            enviarJson(ex, 200, okJson("Ficha guardada exitosamente."));
        }

        private void actualizarFicha(HttpExchange ex, String cedulaOriginal) throws Exception {
            Map<String, String> body = leerBody(ex);
            String cedula = body.getOrDefault("cedula", "").trim();
            String nombre = body.getOrDefault("nombreCompleto", "").trim();
            String rol = body.getOrDefault("rol", "").trim();
            String anoSeccion = body.getOrDefault("anoSeccion", "").trim();
            String rfid = body.getOrDefault("codigoRfid", "").trim();
            String telefono = body.getOrDefault("telefono", "").trim();
            Integer horarioId = obtenerIdNullable(body.get("horarioId"));

            if (cedula.isEmpty() || nombre.isEmpty() || rol.isEmpty()) {
                enviarJson(ex, 400, errorJson("Cédula, nombre y rol son obligatorios."));
                return;
            }
            if (rfid.isEmpty())
                rfid = "CARNET-" + cedula;

            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE personas SET codigo_rfid = ?, cedula = ?, nombre_completo = ?, rol = ?, "
                                    + "ano_seccion = ?, telefono = ?, horario_id = ?, sincronizado = 0 WHERE cedula = ?")) {
                asignarCamposFicha(stmt, rfid, cedula, nombre, rol, anoSeccion, telefono, horarioId);
                stmt.setString(8, cedulaOriginal);
                int filas = stmt.executeUpdate();
                if (filas == 0) {
                    enviarJson(ex, 404, errorJson("Ficha no encontrada."));
                    return;
                }
            }
            enviarJson(ex, 200, okJson("Ficha actualizada exitosamente."));
        }

        private void eliminarFicha(HttpExchange ex, String valor) throws Exception {
            try (Connection conn = DatabaseConnection.conectar()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE personas SET eliminado = 1, sincronizado = 0 WHERE cedula = ? OR codigo_rfid = ?")) {
                    stmt.setString(1, valor);
                    stmt.setString(2, valor);
                    stmt.executeUpdate();
                }
            }
            enviarJson(ex, 200, okJson("Ficha eliminada."));
        }
    }

    // --- HANDLER HORARIOS ---
    static class HorariosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String metodo = ex.getRequestMethod().toUpperCase();
                if (metodo.equals("OPTIONS")) {
                    opciones(ex);
                    return;
                }
                String recurso = rutaRelativa(ex, "/api/horarios");
                if (metodo.equals("GET")) {
                    listarHorarios(ex);
                } else if (metodo.equals("POST")) {
                    crearHorario(ex);
                } else if (metodo.equals("DELETE")) {
                    eliminarHorario(ex, recurso);
                } else {
                    enviarJson(ex, 405, errorJson("Método no soportado."));
                }
            } catch (Exception e) {
                e.printStackTrace();
                enviarJson(ex, 500, errorJson(e.getMessage()));
            }
        }

        private void listarHorarios(HttpExchange ex) throws Exception {
            StringBuilder json = new StringBuilder("[");
            String sql = "SELECT h.id, h.nombre_horario, h.tipo_horario, h.margen_tolerancia_min "
                    + "FROM horarios h ORDER BY h.tipo_horario ASC, h.nombre_horario ASC";
            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                boolean primero = true;
                while (rs.next()) {
                    if (!primero)
                        json.append(",");
                    int id = rs.getInt("id");
                    String nombre = rs.getString("nombre_horario");
                    String tipo = rs.getString("tipo_horario");
                    int tolerancia = rs.getInt("margen_tolerancia_min");
                    Map<Integer, DiaHorario> dias = obtenerDiasHorario(conn, id);

                    StringBuilder semana = new StringBuilder();
                    for (int d = 1; d <= 7; d++) {
                        if (d > 1)
                            semana.append(",");
                        DiaHorario dia = dias.get(d);
                        semana.append("\"").append(d).append("\":{")
                                .append("\"entrada\":").append(json(dia == null ? null : dia.entrada)).append(",")
                                .append("\"salida\":").append(json(dia == null ? null : dia.salida))
                                .append("}");
                    }
                    json.append("{")
                            .append("\"id\":").append(id).append(",")
                            .append("\"nombreHorario\":").append(json(nombre)).append(",")
                            .append("\"tipo\":").append(json(tipo)).append(",")
                            .append("\"tolerancia\":").append(tolerancia);
                    json.append(",\"dias\":{").append(semana).append("}");
                    json.append("}");
                    primero = false;
                }
            }
            json.append("]");
            enviarJson(ex, 200, json.toString());
        }

        private void crearHorario(HttpExchange ex) throws Exception {
            Map<String, Object> root = parsearJsonCompleto(leerCuerpo(ex));
            String tipo = strVal(root == null ? null : root.get("tipo")).trim().toUpperCase();
            if (tipo.isEmpty())
                tipo = "SECCION";
            int tolerancia = intVal(root == null ? null : root.get("tolerancia"));
            if (tolerancia <= 0)
                tolerancia = 15;

            String nombre = "Horario General";
            if ("PERSONAL".equals(tipo)) {
                String cedulaPersona = strVal(root == null ? null : root.get("cedulaPersonal")).trim();
                nombre = "P|" + cedulaPersona;
            } else {
                String ano = strVal(root == null ? null : root.get("ano")).trim();
                String seccion = strVal(root == null ? null : root.get("seccion")).trim();
                nombre = ano + " / " + seccion;
            }

            try (Connection conn = DatabaseConnection.conectar()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO horarios (nombre_horario, tipo_horario, margen_tolerancia_min) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, nombre);
                    stmt.setString(2, tipo);
                    stmt.setInt(3, tolerancia);
                    stmt.executeUpdate();
                }
            }
            enviarJson(ex, 200, okJson("Horario guardado con éxito."));
        }

        private void eliminarHorario(HttpExchange ex, String idTexto) throws Exception {
            int id = Integer.parseInt(idTexto);
            try (Connection conn = DatabaseConnection.conectar()) {
                try (PreparedStatement stmt = conn
                        .prepareStatement("DELETE FROM horario_semanal WHERE horario_id = ?")) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM horarios WHERE id = ?")) {
                    stmt.setInt(1, id);
                    stmt.executeUpdate();
                }
            }
            enviarJson(ex, 200, okJson("Horario eliminado."));
        }
    }

    // --- HANDLER MARCAJES Y ASISTENCIAS (CON INTEGRACIÓN WHATSAPP Y
    // AUTORIZACIONES) ---
    static class AsistenciaHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String metodo = ex.getRequestMethod().toUpperCase();
                if (metodo.equals("OPTIONS")) {
                    opciones(ex);
                    return;
                }
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/marcar") || path.endsWith("/marcaje")) {
                    marcar(ex);
                } else if (path.endsWith("/resumen")) {
                    resumen(ex);
                } else if (path.endsWith("/historial") || path.endsWith("/reportes")) {
                    historial(ex);
                } else if (path.endsWith("/autorizar")) {
                    autorizar(ex);
                } else {
                    enviarJson(ex, 404, errorJson("Ruta no encontrada."));
                }
            } catch (Exception e) {
                e.printStackTrace();
                enviarJson(ex, 500, errorJson(e.getMessage()));
            }
        }

        private void marcar(HttpExchange ex) throws Exception {
            Map<String, String> body = leerBody(ex);
            String codigo = body.getOrDefault("codigo", body.getOrDefault("codigoRfid", "")).trim();
            String tipoEventoSeleccionado = body.getOrDefault("tipoEvento", "AUTO").trim().toUpperCase();

            if (codigo.isEmpty()) {
                enviarJson(ex, 400, errorJson("Ingrese un código o cédula."));
                return;
            }

            synchronized (ultimosMarcajes) {
                LocalDateTime ultimo = ultimosMarcajes.get(codigo);
                if (ultimo != null) {
                    long segundos = Duration.between(ultimo, LocalDateTime.now()).getSeconds();
                    if (segundos < TIEMPO_ESPERA_SEGUNDOS) {
                        long restante = TIEMPO_ESPERA_SEGUNDOS - segundos;
                        enviarJson(ex, 429, "{\"status\":\"DUPLICADO\",\"mensaje\":"
                                + json("Marcaje ignorado. Espere " + restante + " segundos.") + "}");
                        return;
                    }
                }
                ultimosMarcajes.put(codigo, LocalDateTime.now());
            }

            String rfid, cedula, nombre, rol, anoSeccion, telefono;
            Integer horarioId;

            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(
                            "SELECT codigo_rfid, cedula, nombre_completo, rol, COALESCE(ano_seccion, '') AS ano_seccion, "
                                    + "COALESCE(telefono, '') AS telefono, COALESCE(horario_id, 0) AS horario_id "
                                    + "FROM personas WHERE (codigo_rfid = ? OR cedula = ?) AND eliminado = 0")) {
                stmt.setString(1, codigo);
                stmt.setString(2, codigo);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        enviarJson(ex, 404, errorJson("Cédula o carnet no registrado. Verifíquelo en Fichas."));
                        return;
                    }
                    rfid = rs.getString("codigo_rfid");
                    cedula = rs.getString("cedula");
                    nombre = rs.getString("nombre_completo");
                    rol = rs.getString("rol");
                    anoSeccion = rs.getString("ano_seccion");
                    telefono = rs.getString("telefono");
                    horarioId = rs.getInt("horario_id") == 0 ? null : rs.getInt("horario_id");
                }
            }

            String tipoEvento = "ENTRADA";
            if ("SALIDA_TEMPRANA".equals(tipoEventoSeleccionado)) {
                tipoEvento = "SALIDA";
            } else {
                try (Connection conn = DatabaseConnection.conectar();
                        PreparedStatement stmt = conn.prepareStatement(
                                "SELECT tipo_evento FROM asistencias WHERE codigo_rfid = ? ORDER BY id DESC LIMIT 1")) {
                    stmt.setString(1, rfid);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next() && "ENTRADA".equals(rs.getString(1))) {
                            tipoEvento = "SALIDA";
                        }
                    }
                }
            }

            LocalTime ahora = LocalTime.now();
            String horaTexto = ahora.format(HORA);
            String fechaHoraTexto = LocalDateTime.now().format(FECHA_HORA);
            String evaluacion = "SALIDA_TEMPRANA".equals(tipoEventoSeleccionado) ? "SALIDA_ANTICIPADA" : "A TIEMPO";
            boolean notifEnviada = false;

            String uuidMarcaje = UUID.randomUUID().toString();
            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO asistencias (id, codigo_rfid, fecha_hora, tipo_evento, evaluacion, sincronizado) VALUES (?, ?, ?, ?, ?, 0)")) {
                stmt.setString(1, uuidMarcaje);
                stmt.setString(2, rfid);
                stmt.setString(3, fechaHoraTexto);
                stmt.setString(4, tipoEvento);
                stmt.setString(5, evaluacion);
                stmt.executeUpdate();
            }

            // NOTIFICACIÓN AUTOMÁTICA A REPRESENTANTE / PERSONAL (WHATSAPP O SMS)
            if (telefono != null && !telefono.isEmpty()) {
                String institucion = DatabaseConnection.obtenerConfiguracion("NOMBRE_INSTITUCION");
                if (institucion == null || institucion.isEmpty())
                    institucion = "Unidad Educativa";
                String mensajeWp = institucion + ": Estimado representante, se informa que el estudiante "
                        + nombre + " (C.I. " + cedula + ") registró " + tipoEvento + " a las " + horaTexto
                        + " (" + evaluacion + ").";

                NotificacionService.enviarAviso(telefono, mensajeWp);
                notifEnviada = true;
            }

            String jsonResp = "{"
                    + "\"status\":\"OK\","
                    + "\"marcajeId\":" + json(uuidMarcaje) + ","
                    + "\"tipoMarcaje\":" + json(tipoEvento) + ","
                    + "\"evaluacion\":" + json(evaluacion) + ","
                    + "\"hora\":" + json(horaTexto) + ","
                    + "\"fechaHora\":" + json(fechaHoraTexto) + ","
                    + "\"nombreCompleto\":" + json(nombre) + ","
                    + "\"cedula\":" + json(cedula) + ","
                    + "\"rol\":" + json(rol) + ","
                    + "\"anoSeccion\":" + json(anoSeccion) + ","
                    + "\"notificacionWp\":" + notifEnviada + ","
                    + "\"mensaje\":" + json("Marcaje registrado correctamente.")
                    + "}";
            enviarJson(ex, 200, jsonResp);
        }

        private void autorizar(HttpExchange ex) throws Exception {
            Map<String, Object> root = parsearJsonCompleto(leerCuerpo(ex));
            String marcajeId = strVal(root == null ? null : root.get("marcajeId")).trim();
            String tipoPase = strVal(root == null ? null : root.get("tipoPase")).trim().toUpperCase();
            String motivo = strVal(root == null ? null : root.get("motivo")).trim();

            if (marcajeId.isEmpty()) {
                enviarJson(ex, 400, errorJson("El ID del marcaje es obligatorio."));
                return;
            }

            String nuevaEvaluacion = "SALIDA_PASE_FORMAL";
            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE asistencias SET evaluacion = ?, sincronizado = 0 WHERE id = ?")) {
                stmt.setString(1, nuevaEvaluacion);
                stmt.setString(2, marcajeId);
                stmt.executeUpdate();
            }

            enviarJson(ex, 200, okJson("Pase de salida autorizada registrado."));
        }

        private void resumen(HttpExchange ex) throws Exception {
            Map<String, String> q = leerQuery(ex);
            String busqueda = q.getOrDefault("busqueda", "").trim();
            String rol = q.getOrDefault("rol", "").trim();

            StringBuilder json = new StringBuilder("[");
            String sql = "SELECT p.cedula, p.nombre_completo, p.rol, COALESCE(p.ano_seccion, '') AS ano_seccion, "
                    + "COUNT(a.id) AS total_asistencias "
                    + "FROM personas p LEFT JOIN asistencias a ON a.codigo_rfid = p.codigo_rfid "
                    + "WHERE p.eliminado = 0 GROUP BY p.cedula, p.nombre_completo, p.rol, p.ano_seccion ORDER BY p.nombre_completo ASC";

            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                boolean primero = true;
                while (rs.next()) {
                    if (!primero)
                        json.append(",");
                    json.append("{")
                            .append("\"cedula\":").append(json(rs.getString("cedula"))).append(",")
                            .append("\"nombre\":").append(json(rs.getString("nombre_completo"))).append(",")
                            .append("\"rol\":").append(json(rs.getString("rol"))).append(",")
                            .append("\"seccion\":").append(json(rs.getString("ano_seccion"))).append(",")
                            .append("\"asistencias\":").append(rs.getInt("total_asistencias")).append(",")
                            .append("\"puntual\":").append(rs.getInt("total_asistencias")).append(",")
                            .append("\"retardos\":0,")
                            .append("\"salidasTempranas\":0,")
                            .append("\"permisos\":0")
                            .append("}");
                    primero = false;
                }
            }
            json.append("]");
            enviarJson(ex, 200, json.toString());
        }

        private void historial(HttpExchange ex) throws Exception {
            StringBuilder json = new StringBuilder("[");
            String sql = "SELECT a.fecha_hora, a.tipo_evento, a.evaluacion, COALESCE(p.cedula, '') AS cedula, "
                    + "COALESCE(p.nombre_completo, 'Desconocido') AS nombre_completo, COALESCE(p.rol, '') AS rol, "
                    + "COALESCE(p.ano_seccion, '') AS ano_seccion "
                    + "FROM asistencias a LEFT JOIN personas p ON a.codigo_rfid = p.codigo_rfid ORDER BY a.fecha_hora DESC LIMIT 200";

            try (Connection conn = DatabaseConnection.conectar();
                    PreparedStatement stmt = conn.prepareStatement(sql);
                    ResultSet rs = stmt.executeQuery()) {
                boolean primero = true;
                while (rs.next()) {
                    if (!primero)
                        json.append(",");
                    json.append("{")
                            .append("\"fechaHora\":").append(json(rs.getString("fecha_hora"))).append(",")
                            .append("\"cedula\":").append(json(rs.getString("cedula"))).append(",")
                            .append("\"nombre\":").append(json(rs.getString("nombre_completo"))).append(",")
                            .append("\"rol\":").append(json(rs.getString("rol"))).append(",")
                            .append("\"seccion\":").append(json(rs.getString("ano_seccion"))).append(",")
                            .append("\"tipo\":").append(json(rs.getString("tipo_evento"))).append(",")
                            .append("\"evaluacion\":").append(json(rs.getString("evaluacion")))
                            .append("}");
                    primero = false;
                }
            }
            json.append("]");
            enviarJson(ex, 200, json.toString());
        }
    }

    // --- HANDLER PERMISOS ---
    static class PermisosHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if ("OPTIONS".equals(ex.getRequestMethod().toUpperCase())) {
                    opciones(ex);
                    return;
                }
                Map<String, String> body = leerBody(ex);
                String cedula = body.getOrDefault("cedula", "").trim();
                String motivo = body.getOrDefault("motivo", "").trim();

                try (Connection conn = DatabaseConnection.conectar();
                        PreparedStatement stmt = conn.prepareStatement(
                                "INSERT INTO permisos_salida (codigo_rfid_estudiante, fecha_emision, motivo, estado) VALUES (?, ?, ?, 'APROBADO')")) {
                    stmt.setString(1, cedula);
                    stmt.setString(2, LocalDate.now().toString());
                    stmt.setString(3, motivo);
                    stmt.executeUpdate();
                }
                enviarJson(ex, 200, okJson("Permiso registrado correctamente."));
            } catch (Exception e) {
                enviarJson(ex, 500, errorJson(e.getMessage()));
            }
        }
    }

    // --- HANDLER HISTORIAL RECIENTE ---
    static class HistorialRecienteHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if ("OPTIONS".equals(ex.getRequestMethod().toUpperCase())) {
                    opciones(ex);
                    return;
                }
                StringBuilder json = new StringBuilder("[");
                String sql = "SELECT a.codigo_rfid, COALESCE(p.nombre_completo, 'Desconocido') AS nombre, "
                        + "a.fecha_hora, a.tipo_evento, a.evaluacion "
                        + "FROM asistencias a LEFT JOIN personas p ON a.codigo_rfid = p.codigo_rfid ORDER BY a.fecha_hora DESC LIMIT 50";
                try (Connection conn = DatabaseConnection.conectar();
                        PreparedStatement stmt = conn.prepareStatement(sql);
                        ResultSet rs = stmt.executeQuery()) {
                    boolean primero = true;
                    while (rs.next()) {
                        if (!primero)
                            json.append(",");
                        json.append("{")
                                .append("\"codigoRfid\":").append(json(rs.getString("codigo_rfid"))).append(",")
                                .append("\"nombre\":").append(json(rs.getString("nombre"))).append(",")
                                .append("\"fechaHora\":").append(json(rs.getString("fecha_hora"))).append(",")
                                .append("\"tipoEvento\":").append(json(rs.getString("tipo_evento"))).append(",")
                                .append("\"evaluacion\":").append(json(rs.getString("evaluacion")))
                                .append("}");
                        primero = false;
                    }
                }
                json.append("]");
                enviarJson(ex, 200, json.toString());
            } catch (Exception e) {
                enviarJson(ex, 500, errorJson(e.getMessage()));
            }
        }
    }

    // --- HANDLER CONFIGURACIÓN (GSM Y WHATSAPP) ---
    static class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String metodo = ex.getRequestMethod().toUpperCase();
                if (metodo.equals("OPTIONS")) {
                    opciones(ex);
                    return;
                }
                String path = ex.getRequestURI().getPath();
                if (path.endsWith("/probar-notificacion") || path.endsWith("/probar")) {
                    probarEnvio(ex);
                } else if (metodo.equals("GET")) {
                    listarConfig(ex);
                } else if (metodo.equals("POST")) {
                    guardarConfig(ex);
                } else {
                    enviarJson(ex, 405, errorJson("Método no soportado."));
                }
            } catch (Exception e) {
                e.printStackTrace();
                enviarJson(ex, 500, errorJson(e.getMessage()));
            }
        }

        private void listarConfig(HttpExchange ex) throws Exception {
            Map<String, String> cfg = DatabaseConnection.obtenerTodasConfiguraciones();
            StringBuilder json = new StringBuilder("{");
            boolean primero = true;
            for (Map.Entry<String, String> par : cfg.entrySet()) {
                if (!primero)
                    json.append(",");
                json.append(json(par.getKey())).append(":").append(json(par.getValue()));
                primero = false;
            }
            json.append("}");
            enviarJson(ex, 200, json.toString());
        }

        private void guardarConfig(HttpExchange ex) throws Exception {
            Map<String, String> body = leerBody(ex);
            for (Map.Entry<String, String> entry : body.entrySet()) {
                DatabaseConnection.guardarConfiguracion(entry.getKey(), entry.getValue());
            }
            enviarJson(ex, 200, okJson("Configuración guardada exitosamente."));
        }

        private void probarEnvio(HttpExchange ex) throws Exception {
            Map<String, String> body = leerBody(ex);
            String telefono = body.getOrDefault("telefono", "").trim();
            String mensaje = body.getOrDefault("mensaje", "Prueba de notificación institucional.").trim();

            if (telefono.isEmpty()) {
                enviarJson(ex, 400, errorJson("Indique el teléfono de destino."));
                return;
            }
            String detalle = NotificacionService.probarEnvio(telefono, mensaje);
            enviarJson(ex, 200, "{\"status\":\"OK\",\"detalle\":" + json(detalle) + "}");
        }
    }

    // --- HANDLER SINCRONIZACIÓN HÍBRIDA (ENTRE LOCAL Y NUBE) ---
    static class SincronizacionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if ("OPTIONS".equals(ex.getRequestMethod().toUpperCase())) {
                    opciones(ex);
                    return;
                }
                // Proceso de intercambio de datos sin sincronizar
                int enviados = 0, recibidos = 0;
                try (Connection conn = DatabaseConnection.conectar()) {
                    // Marcar registros pendientes locales como sincronizados tras transmisión
                    try (PreparedStatement stmt = conn
                            .prepareStatement("UPDATE asistencias SET sincronizado = 1 WHERE sincronizado = 0")) {
                        enviados = stmt.executeUpdate();
                    }
                    try (PreparedStatement stmt = conn
                            .prepareStatement("UPDATE personas SET sincronizado = 1 WHERE sincronizado = 0")) {
                        stmt.executeUpdate();
                    }
                }
                enviarJson(ex, 200,
                        "{\"status\":\"OK\",\"enviados\":" + enviados + ",\"recibidos\":" + recibidos + "}");
            } catch (Exception e) {
                enviarJson(ex, 500, errorJson("Error de sincronización: " + e.getMessage()));
            }
        }
    }

    // --- AUXILIARES Y HERRAMIENTAS ---
    private static void avisarMarcajeStaff(String telefono, String nombre, String rol, String tipoEvento, String hora,
            String evaluacion) {
        if (telefono == null || telefono.isEmpty())
            return;
        String institucion = DatabaseConnection.obtenerConfiguracion("NOMBRE_INSTITUCION");
        String mensaje = (institucion != null ? institucion : "Escuela") + ": Aviso de marcaje. " + nombre + " (" + rol
                + ") registró "
                + tipoEvento + " a las " + hora + ". Evaluación: " + evaluacion + ".";
        NotificacionService.enviarAviso(telefono, mensaje);
    }

    private static void asignarCamposFicha(PreparedStatement stmt, String rfid, String cedula, String nombre,
            String rol, String anoSeccion, String telefono, Integer horarioId) throws SQLException {
        stmt.setString(1, rfid);
        stmt.setString(2, cedula);
        stmt.setString(3, nombre);
        stmt.setString(4, rol);
        stmt.setString(5, anoSeccion);
        stmt.setString(6, telefono);
        if (horarioId == null)
            stmt.setNull(7, java.sql.Types.INTEGER);
        else
            stmt.setInt(7, horarioId);
    }

    private static Integer obtenerIdNullable(String valor) {
        if (valor == null || valor.trim().isEmpty())
            return null;
        try {
            int id = Integer.parseInt(valor.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<Integer, DiaHorario> obtenerDiasHorario(Connection conn, int horarioId) throws SQLException {
        Map<Integer, DiaHorario> dias = new LinkedHashMap<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT dia, hora_entrada, hora_salida FROM horario_semanal WHERE horario_id = ?")) {
            stmt.setInt(1, horarioId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DiaHorario d = new DiaHorario();
                    d.entrada = rs.getString("hora_entrada");
                    d.salida = rs.getString("hora_salida");
                    dias.put(rs.getInt("dia"), d);
                }
            }
        }
        return dias;
    }

    private static class DiaHorario {
        String entrada;
        String salida;
    }

    private static void opciones(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        ex.sendResponseHeaders(204, -1);
    }

    private static void enviarJson(HttpExchange ex, int statusCode, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(statusCode, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static String json(String s) {
        if (s == null)
            return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private static String errorJson(String mensaje) {
        return "{\"status\":\"ERROR\",\"mensaje\":" + json(mensaje) + "}";
    }

    private static String okJson(String mensaje) {
        return "{\"status\":\"OK\",\"mensaje\":" + json(mensaje) + "}";
    }

    private static String leerCuerpo(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> leerBody(HttpExchange ex) throws IOException {
        return parsearJson(leerCuerpo(ex));
    }

    private static Map<String, String> leerQuery(HttpExchange ex) {
        Map<String, String> mapa = new HashMap<>();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty())
            return mapa;
        for (String par : query.split("&")) {
            int idx = par.indexOf('=');
            if (idx < 0)
                continue;
            try {
                mapa.put(
                        URLDecoder.decode(par.substring(0, idx), StandardCharsets.UTF_8.name()),
                        URLDecoder.decode(par.substring(idx + 1), StandardCharsets.UTF_8.name()));
            } catch (Exception e) {
            }
        }
        return mapa;
    }

    private static Map<String, String> parsearJson(String body) {
        Map<String, String> mapa = new HashMap<>();
        String contenido = body == null ? "" : body.trim();
        if (contenido.isEmpty())
            return mapa;
        if (contenido.startsWith("{"))
            contenido = contenido.substring(1);
        if (contenido.endsWith("}"))
            contenido = contenido.substring(0, contenido.length() - 1);
        for (String par : dividirPares(contenido)) {
            int idx = par.indexOf(':');
            if (idx < 0)
                continue;
            mapa.put(limpiarValor(par.substring(0, idx)), limpiarValor(par.substring(idx + 1)));
        }
        return mapa;
    }

    private static String limpiarValor(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
            return s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    private static List<String> dividirPares(String s) {
        List<String> res = new ArrayList<>();
        boolean enComillas = false;
        int inicio = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                if (i == 0 || s.charAt(i - 1) != '\\')
                    enComillas = !enComillas;
            } else if (c == ',' && !enComillas) {
                res.add(s.substring(inicio, i));
                inicio = i + 1;
            }
        }
        res.add(s.substring(inicio));
        return res;
    }

    private static Map<String, Object> parsearJsonCompleto(String texto) {
        if (texto == null || texto.trim().isEmpty())
            return null;
        Object valor = new ParserJson(texto.trim()).parsearValor();
        return valor instanceof Map ? (Map<String, Object>) valor : null;
    }

    private static String strVal(Object o) {
        if (o == null)
            return "";
        if (o instanceof String)
            return (String) o;
        return String.valueOf(o);
    }

    private static int intVal(Object o) {
        if (o == null)
            return 0;
        if (o instanceof Number)
            return ((Number) o).intValue();
        try {
            return Integer.parseInt(strVal(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static class ParserJson {
        private final String s;
        private int pos;

        ParserJson(String s) {
            this.s = s;
        }

        Object parsearValor() {
            saltarEspacios();
            if (pos >= s.length())
                return null;
            char c = s.charAt(pos);
            if (c == '{')
                return parsearObjeto();
            if (c == '[')
                return parsearArreglo();
            if (c == '"')
                return parsearCadena();
            return parsearLiteralONumero();
        }

        private void saltarEspacios() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos)))
                pos++;
        }

        private Map<String, Object> parsearObjeto() {
            Map<String, Object> mapa = new LinkedHashMap<>();
            pos++;
            saltarEspacios();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return mapa;
            }
            while (true) {
                saltarEspacios();
                if (pos >= s.length() || s.charAt(pos) != '"')
                    break;
                String clave = parsearCadena();
                saltarEspacios();
                if (pos < s.length() && s.charAt(pos) == ':')
                    pos++;
                Object valor = parsearValor();
                mapa.put(clave, valor);
                saltarEspacios();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < s.length() && s.charAt(pos) == '}')
                    pos++;
                break;
            }
            return mapa;
        }

        private List<Object> parsearArreglo() {
            List<Object> lista = new ArrayList<>();
            pos++;
            saltarEspacios();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return lista;
            }
            while (true) {
                lista.add(parsearValor());
                saltarEspacios();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    continue;
                }
                if (pos < s.length() && s.charAt(pos) == ']')
                    pos++;
                break;
            }
            return lista;
        }

        private String parsearCadena() {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\\' && pos + 1 < s.length()) {
                    char siguiente = s.charAt(pos + 1);
                    switch (siguiente) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        default:
                            sb.append(siguiente);
                    }
                    pos += 2;
                    continue;
                }
                if (c == '"') {
                    pos++;
                    break;
                }
                sb.append(c);
                pos++;
            }
            return sb.toString();
        }

        private Object parsearLiteralONumero() {
            int inicio = pos;
            while (pos < s.length() && ",]}".indexOf(s.charAt(pos)) < 0)
                pos++;
            String token = s.substring(inicio, pos).trim();
            if ("true".equals(token))
                return Boolean.TRUE;
            if ("false".equals(token))
                return Boolean.FALSE;
            if ("null".equals(token))
                return null;
            try {
                return Integer.valueOf(token);
            } catch (NumberFormatException e) {
            }
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException e) {
            }
            return token;
        }
    }

    private static String rutaRelativa(HttpExchange ex, String contexto) {
        String path = ex.getRequestURI().getPath();
        if (path.startsWith(contexto))
            path = path.substring(contexto.length());
        if (path.startsWith("/"))
            path = path.substring(1);
        return path;
    }
}
