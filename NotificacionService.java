package service;

import database.DatabaseConnection;

import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class NotificacionService {

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static String modo() {
        String m = DatabaseConnection.obtenerConfiguracion("NOTIF_MODO");
        if (m == null || m.trim().isEmpty()) {
            m = DatabaseConnection.obtenerConfiguracion("GSM_MODO");
        }
        return m == null || m.trim().isEmpty() ? "CONSOLA" : m.trim().toUpperCase();
    }

    public static void enviarAviso(String telefono, String mensaje) {
        if (telefono == null || telefono.trim().isEmpty()) {
            System.out.println("[NOTIF]: Sin teléfono de destino. Se omite el aviso.");
            return;
        }
        String resultado = entregar(telefono.trim(), mensaje);
        System.out.println("[NOTIF][" + telefono + "]: " + mensaje
                + (resultado.isEmpty() ? "" : " | " + resultado));
    }

    public static String probarEnvio(String telefono, String mensaje) {
        if (telefono == null || telefono.trim().isEmpty())
            return "Indique un teléfono de destino.";
        return entregar(telefono.trim(), mensaje);
    }

    private static String entregar(String telefono, String mensaje) {
        String m = modo();
        if ("NINGUNO".equals(m)) {
            return "MODO NINGUNO: envío desactivado.";
        }

        StringBuilder log = new StringBuilder();

        // 1. Envío por WhatsApp
        if ("WHATSAPP".equals(m) || "AMBOS".equals(m)) {
            try {
                String resWp = enviarWhatsApp(telefono, mensaje);
                log.append("[WP]: ").append(resWp).append(" ");
            } catch (Throwable t) {
                System.out.println("[NOTIF][WHATSAPP]: Error al enviar -> " + t);
                log.append("[WP-ERR]: ").append(t.getMessage()).append(" ");
            }
        }

        // 2. Envío por Módem GSM (SMS)
        if ("SMS".equals(m) || "AMBOS".equals(m)) {
            try {
                String resSms = enviarSmsGsm(telefono, mensaje);
                log.append("[SMS]: ").append(resSms);
            } catch (Throwable t) {
                System.out.println("[NOTIF][GSM]: Error al enviar SMS -> " + t);
                log.append("[SMS-ERR]: ").append(t.getMessage());
            }
        }

        // 3. Impresión en Consola
        if ("CONSOLA".equals(m)) {
            System.out.println("[NOTIF][CONSOLA] -> " + telefono + ": " + mensaje);
            return "CONSOLA: aviso impreso en la consola del servidor.";
        }

        return log.toString().trim();
    }

    // --- INTEGRACIÓN WHATSAPP API (GREEN API / REST HTTP) ---
    private static String enviarWhatsApp(String telefono, String mensaje) throws Exception {
        String urlBase = DatabaseConnection.obtenerConfiguracion("WP_URL");
        String instanceId = DatabaseConnection.obtenerConfiguracion("WP_INSTANCE");
        String apiToken = DatabaseConnection.obtenerConfiguracion("WP_TOKEN");

        if (urlBase == null || urlBase.trim().isEmpty() ||
                instanceId == null || instanceId.trim().isEmpty() ||
                apiToken == null || apiToken.trim().isEmpty()) {
            return "Parámetros de WhatsApp no configurados en BD.";
        }

        // Formatear número a código internacional (Ej: 04141234567 -> 584141234567)
        String numLimpio = telefono.replaceAll("[^0-9]", "");
        if (numLimpio.startsWith("0")) {
            numLimpio = "58" + numLimpio.substring(1);
        }
        String chatId = numLimpio + "@c.us";

        String endpoint = urlBase.replaceAll("/+$", "") + "/waInstance" + instanceId.trim() + "/sendMessage/"
                + apiToken.trim();

        String mensajeJson = mensaje.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");

        String jsonPayload = String.format("{\"chatId\":\"%s\",\"message\":\"%s\"}", chatId, mensajeJson);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return "WhatsApp enviado correctamente";
        } else {
            return "Error HTTP WhatsApp " + response.statusCode() + ": " + response.body();
        }
    }

    // --- INTEGRACIÓN MÓDEM GSM (SMS VIA JSERIALCOMM REFLECTION) ---
    private static String enviarSmsGsm(String telefono, String mensaje) throws Exception {
        Class<?> clsSerialPort;
        try {
            clsSerialPort = Class.forName("com.fazecast.jSerialComm.SerialPort");
        } catch (ClassNotFoundException e) {
            return "jSerialComm no está en lib. El aviso se imprimió en consola.";
        }

        String puertoNombre = DatabaseConnection.obtenerConfiguracion("GSM_PUERTO");
        if (puertoNombre == null || puertoNombre.trim().isEmpty())
            puertoNombre = "COM3";

        int baud = 9600;
        String baudTexto = DatabaseConnection.obtenerConfiguracion("GSM_BAUD");
        if (baudTexto != null && !baudTexto.trim().isEmpty()) {
            try {
                baud = Integer.parseInt(baudTexto.trim());
            } catch (Exception e) {
                // baud por defecto 9600
            }
        }

        Object puerto = clsSerialPort.getMethod("getCommPort", String.class).invoke(null, puertoNombre);
        if (puerto == null)
            return "Puerto " + puertoNombre + " no encontrado.";

        Boolean abierto = (Boolean) clsSerialPort.getMethod("openPort").invoke(puerto);
        if (!abierto)
            return "No se pudo abrir el puerto " + puertoNombre + ".";

        try {
            clsSerialPort.getMethod("setComPortParameters", int.class, int.class, int.class, int.class)
                    .invoke(puerto, baud, 8, 1, 0);
            clsSerialPort.getMethod("setComPortTimeouts", int.class, int.class, int.class)
                    .invoke(puerto, 0, 2000, 2000);

            OutputStream os = (OutputStream) clsSerialPort.getMethod("getOutputStream").invoke(puerto);
            esperar(2000);

            enviarComando(os, "AT");
            enviarComando(os, "AT+CMGF=1");
            enviarComando(os, "AT+CMGS=\"" + telefono + "\"");
            esperar(800);

            os.write((mensaje + "\u001A").getBytes(StandardCharsets.UTF_8));
            os.flush();
            esperar(3000);

            return "SMS enviado por " + puertoNombre + " (" + baud + " baud).";
        } finally {
            clsSerialPort.getMethod("closePort").invoke(puerto);
        }
    }

    private static void enviarComando(OutputStream os, String comando) throws Exception {
        os.write((comando + "\r").getBytes(StandardCharsets.ISO_8859_1));
        os.flush();
        esperar(700);
    }

    private static void esperar(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
