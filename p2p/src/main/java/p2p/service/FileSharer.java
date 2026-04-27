package p2p.service;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.util.HashMap;
import java.net.Socket;

import p2p.utils.UploadUtils;

    
public class FileSharer {
        private HashMap<Integer,String> availablefiles;
        public FileSharer(){
            availablefiles=new HashMap<>();
        }
        public int offerFile(String filePath){
            int port;
            while (true) {
                port=UploadUtils.getRandomPort();
                if(!availablefiles.containsKey(port)){
                  availablefiles.put(port,filePath);
                  return port;
                }
            }
        }
        public void startFileSharer(int port){
            String filePath=availablefiles.get(port);
            if(filePath==null){
                // Code to start sharing the file at filePath on the given port
                System.out.println("No file found for this port: " + port);
                return;
            } 
            try(ServerSocket serverSocket =new ServerSocket(port)){
                System.out.println("Serving File" +new File(filePath).getName()+ " on port: " + port);
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());
                // Code to send the file to the client
                new Thread(new FileSenderHandler(clientSocket, filePath)).start();
            }
            catch (IOException e) {
                System.err.println("Error starting file sharer: " + e.getMessage());
            }
        }

        private static class FileSenderHandler implements Runnable{
            private final Socket clientSocket;
            private final String filePath;
            public FileSenderHandler(Socket clientSocket, String filePath){
                this.clientSocket=clientSocket;
                this.filePath=filePath;
            }

            @Override
            public void run(){
                try (FileInputStream fis = new FileInputStream(filePath)){
                    // Code to send the file to the client
                    OutputStream os = clientSocket.getOutputStream();
                    String fileName = new File(filePath).getName();
                    String header = "Filename: " + fileName + "\n";
                    os.write(header.getBytes());

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                    System.out.println("File sent successfully to client: " + clientSocket.getInetAddress());

                } catch (Exception e) {
                    System.err.println("Error sending file: " + e.getMessage());
                }
                finally {
                    try {
                        clientSocket.close();
                    } catch (IOException e) {
                        System.err.println("Error closing client socket: " + e.getMessage());
                    }
                }
            }
        }
    }
