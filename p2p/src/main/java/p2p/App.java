package p2p;

import p2p.controllers.FileController;

/**
 * Hello world!
 */
public class App {
    public static void main(String[] args) {
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
            FileController fileController = new FileController(port);
            fileController.start();
            System.out.println("PeerLink is running on port " + port);
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down PeerLink...");
                fileController.stop();
            }));
            
            // On many cloud platforms, reading from System.in might cause issues or block.
            // For a cloud service, we usually just want to keep the main thread alive.
            System.out.println("Application started. Keep-alive active.");
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
