package p2p;

import p2p.controllers.FileController;

/**
 * Hello world!
 */
public class App {
    public static void main(String[] args) {
        try{
            FileController fileController = new FileController(8080);
            fileController.start();
            System.out.println("PeerLink is running on port 8080");
            System.out.println("UI is available at http://localhost:8080");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down PeerLink...");
                fileController.stop();
            }));
            System.out.println("Press ENTER to stop the application.");
            System.in.read();
        } catch (Exception e) {
            e.printStackTrace();    
        }
    }
}
