package p2p.controllers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.io.IOUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import p2p.service.FileSharer;

public class FileController {

    private final HttpServer server;
    private final String uploadDir;
    private final ExecutorService executorService;

    public FileController(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.uploadDir = System.getProperty("java.io.tmpdir") + File.separator + "peerlink-uploads";
        this.executorService = Executors.newFixedThreadPool(10);

        File uploadDirFile = new File(uploadDir);
        if (!uploadDirFile.exists()) {
            uploadDirFile.mkdirs();
        }

        server.createContext("/api/upload", new UploadHandler());
        server.createContext("/api/download", new DownloadHandler());
        server.createContext("/", new CORSHandler());
        server.setExecutor(executorService);
    }

    public void start() {
        server.start();
        System.out.println("FileController started on port: " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        executorService.shutdown();
        System.out.println("FileController stopped.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CORS / 404 fallback handler
    // ─────────────────────────────────────────────────────────────────────────
    private class CORSHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String response = "Not Found";
            exchange.sendResponseHeaders(404, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Upload handler  (POST /upload)
    // ─────────────────────────────────────────────────────────────────────────
    private class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods", "POST");

            // Only allow POST
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // Require multipart/form-data
            Headers requestHeaders = exchange.getRequestHeaders();
            String contentType = requestHeaders.getFirst("Content-Type");
            if (contentType == null || !contentType.startsWith("multipart/form-data")) {
                String response = "Unsupported Media Type";
                exchange.sendResponseHeaders(415, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            try {
                // Parse multipart body
                String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                IOUtils.copy(exchange.getRequestBody(), baos);
                byte[] requestBody = baos.toByteArray();

                Multiparcer parcer = new Multiparcer(requestBody, boundary);
                Multiparcer.ParseResult result = parcer.parse();

                if (result != null) {
                    // Save uploaded file to disk
                    File outputFile = new File(uploadDir, result.fileName);
                    String filePath = uploadDir + File.separator + result.fileName;
                    try (OutputStream fos = new java.io.FileOutputStream(outputFile)) {
                        fos.write(result.fileContent);
                    }

                    // Start a FileSharer on a random port so peers can download it
                    FileSharer fileSharer = new FileSharer();
                    int port = fileSharer.offerFile(filePath);
                    new Thread(() -> fileSharer.startFileSharer(port)).start();

                    // FIX: send ONE response only (removed the duplicate sendResponseHeaders call)
                    String jsonResponse = "{\"port\":" + port + ",\"fileName\":\"" + result.fileName + "\"}";
                    headers.add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jsonResponse.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(jsonResponse.getBytes());
                    }

                } else {
                    String response = "Failed to parse multipart data";
                    exchange.sendResponseHeaders(400, response.getBytes().length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download handler  (GET /download/{port})
    // ─────────────────────────────────────────────────────────────────────────
    private class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");

            // Only allow GET
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                String response = "Method Not Allowed";
                exchange.sendResponseHeaders(405, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
                return;
            }

            // Extract port number from URL path  e.g. /download/54321
            String path = exchange.getRequestURI().getPath();
            String portStr = path.substring(path.lastIndexOf("/") + 1);

            try {
                int port = Integer.parseInt(portStr);

                // Connect to the FileSharer socket and stream the file back
                try (Socket socket = new Socket("localhost", port)) {
                    InputStream socketInpt = socket.getInputStream();

                    // Write socket data to a temp file first (we need file length for Content-Length header)
                    File tempFile = File.createTempFile("download-", ".tmp");
                    String filename = "downloaded-file";

                    // FIX: corrected brace structure – fileOut try-with-resources now properly closed
                    //      before we start sending headers and response body
                    try (OutputStream fileOut = new java.io.FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;

                        // Read the "Filename: <name>\n" header line sent by FileSharer
                        ByteArrayOutputStream headerBaos = new ByteArrayOutputStream();
                        int b;
                        while ((b = socketInpt.read()) != -1) {
                            if (b == '\n') break;
                            headerBaos.write(b);
                        }
                        String header = headerBaos.toString().trim();
                        if (header.startsWith("Filename: ")) {
                            filename = header.substring("Filename: ".length());
                        }

                        // Read the rest (actual file bytes)
                        while ((bytesRead = socketInpt.read(buffer)) != -1) {
                            fileOut.write(buffer, 0, bytesRead);
                        }
                    } // tempFile is now fully written and closed

                    // Send file back to the HTTP client
                    headers.add("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                    headers.add("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, tempFile.length());

                    try (OutputStream os = exchange.getResponseBody();
                         FileInputStream fis = new FileInputStream(tempFile)) {
                        byte[] outBuffer = new byte[4096];
                        int outBytesRead;
                        while ((outBytesRead = fis.read(outBuffer)) != -1) {
                            os.write(outBuffer, 0, outBytesRead);
                        }
                    } finally {
                        tempFile.delete(); // clean up temp file after sending
                    }
                }

            } catch (NumberFormatException e) {
                String response = "Invalid port in URL";
                exchange.sendResponseHeaders(400, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            } catch (Exception e) {
                String response = "Failed to download file: " + e.getMessage();
                exchange.sendResponseHeaders(500, response.getBytes().length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multipart parser  (kept as static inner class so it is self-contained)
    // ─────────────────────────────────────────────────────────────────────────
    private static class Multiparcer {
        private final byte[] data;
        private final String boundary;

        public Multiparcer(byte[] data, String boundary) {
            this.data = data;
            this.boundary = boundary;
        }

        public ParseResult parse() {
            try {
                String dataAsString = new String(data);

                // Extract filename
                String fileNameMarker = "filename=\"";
                int fileNameStart = dataAsString.indexOf(fileNameMarker);
                if (fileNameStart == -1) return null;
                fileNameStart += fileNameMarker.length();
                int fileNameEnd = dataAsString.indexOf("\"", fileNameStart);
                String fileName = dataAsString.substring(fileNameStart, fileNameEnd);

                // Extract Content-Type (optional)
                String contentType = null;
                String contentTypeMarker = "Content-Type: ";
                int contentTypeStart = dataAsString.indexOf(contentTypeMarker);
                if (contentTypeStart != -1) {
                    contentTypeStart += contentTypeMarker.length();
                    int contentTypeEnd = dataAsString.indexOf("\r\n", contentTypeStart);
                    contentType = dataAsString.substring(contentTypeStart, contentTypeEnd);
                }

                // Find where headers end and file bytes begin
                String headerEndMarker = "\r\n\r\n";
                int headerEnd = dataAsString.indexOf(headerEndMarker);
                if (headerEnd == -1) return null;
                int contentStart = headerEnd + headerEndMarker.length();

                // Find closing boundary
                byte[] boundaryBytes = ("\r\n--" + boundary + "--").getBytes();
                int contentEnd = findSequence(data, boundaryBytes, contentStart);
                if (contentEnd == -1) {
                    boundaryBytes = ("\r\n--" + boundary).getBytes();
                    contentEnd = findSequence(data, boundaryBytes, contentStart);
                }
                if (contentEnd == -1 || contentEnd <= contentStart) return null;

                byte[] fileContent = new byte[contentEnd - contentStart];
                System.arraycopy(data, contentStart, fileContent, 0, fileContent.length);
                return new ParseResult(fileName, fileContent, contentType);

            } catch (Exception e) {
                return null;
            }
        }

        public static class ParseResult {
            public final String fileName;
            public final byte[] fileContent;

            public ParseResult(String fileName, byte[] fileContent, String contentType) {
                this.fileName = fileName;
                this.fileContent = fileContent;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility: find byte sequence in data array
    // ─────────────────────────────────────────────────────────────────────────
    private static int findSequence(byte[] data, byte[] sequence, int startPos) {
        outer:
        for (int i = startPos; i <= data.length - sequence.length; i++) {
            for (int j = 0; j < sequence.length; j++) {
                if (data[i + j] != sequence[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}