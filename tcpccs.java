package ChatServer;
//client side
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class tcpccs {
  public static void main(String[] args){
    final ConcurrentHashMap<String, String> fileMap = new ConcurrentHashMap<>();
    final BlockingQueue<String> sender = new LinkedBlockingQueue<>();
    final AtomicBoolean running = new AtomicBoolean(true);

    final String ERROR_MSG = "Usage - java counterclient.java host(String) port(Integer) name(String)";
    if (args.length != 3) {
      System.err.println(ERROR_MSG);
      return;
    }

    String host = args[0];
    String inputPort = args[1];
    String name = args[2];

    int port;
    try {
      port = Integer.parseInt(inputPort);
    } catch (NumberFormatException e) {
      System.err.println((ERROR_MSG));
      return;
    }

    try{
      Socket socket = new Socket(host, port);
      Thread in = new Thread(new InputHandler(socket, name, fileMap, sender, running));
      Thread out = new Thread(new OutputHandler(socket, name, sender, running));
      Thread toServer = new Thread(new QueueSender(socket, sender));
      in.start();
      out.start();
      toServer.start();

    } catch (IOException e) {
      System.err.println("Cannot connect to the server " + host + ":" + port);
    }
  }

  static class InputHandler implements Runnable{
    private Socket socket;
    private String name;
    private final ConcurrentHashMap<String, String> fileMap;
    private final BlockingQueue<String> sender;
    private final AtomicBoolean running;

    public InputHandler(Socket socket, String name, ConcurrentHashMap<String,String> fileMap, BlockingQueue<String> sender,
    AtomicBoolean running){
      this.socket = socket;
      this.name = name;
      this.fileMap = fileMap;
      this.sender = sender;
      this.running = running;
    }

    public void run(){
      try {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        boolean quit = true;
        System.out.println("Connected to the server. You can start sending messages.");
        while (quit){
          String serverInput = in.readLine();
          if (serverInput == null) {
            System.out.println("You have been disconnected from the server.");
            running.set(false);
            try {
              sender.put("/quit");
            } catch (InterruptedException e) {e.printStackTrace();}
            return;
          } 
          if (serverInput.equals("/quit")){
            quit = false;
            socket.close();
          } else if(serverInput.charAt(0) == '/'){
            String[] tokenServerInput = serverInput.split("/");
            if (tokenServerInput[1].equals("accept")){
              //token length needs to be 4
              if(tokenServerInput.length != 3) {
                System.out.println("Address command failed");
              } else {
                ServerSocket clientFileServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                InetAddress addr = clientFileServer.getInetAddress();
                String fileAddr = addr.getHostAddress();
                int port = clientFileServer.getLocalPort();
                String sendAddr = "/address " + fileAddr + "/" + port + "/" + tokenServerInput[2];
                try {
                  sender.put("[" + name + "] " + sendAddr);
                } catch (InterruptedException e){
                  System.err.println("Error message: " + e); 
                } 
                Thread t = new Thread(new ClientFileServer(clientFileServer, fileMap));
                t.start();
              }
            } else if (tokenServerInput[1].equals("reject")){
              if(tokenServerInput.length != 3) {
                System.out.println("Reject command failed");
              } else {
                fileMap.remove(tokenServerInput[2]);
              }
            } else if (tokenServerInput[1].equals("send")){
              if(tokenServerInput.length != 4) {
                System.out.println("Send command failed");
              } else {
                fileMap.put(tokenServerInput[2], tokenServerInput[3]);
              }
            } else if (tokenServerInput[1].equals("address")){
              if(tokenServerInput.length != 5) {
                System.out.println("Address command failed");
              } else {
                String address = tokenServerInput[2];
                int port = Integer.parseInt(tokenServerInput[3]);
                String recipientName = tokenServerInput[4];
                Thread t = new Thread(new FileClient(address, port, recipientName));
                t.start();
              }
            }
          } else {
            System.out.println(serverInput);
          }
        }
      } catch (IOException e){
        System.err.println("Error reading input from server");
      }
    }
  }

  static class OutputHandler implements Runnable{
    private Socket socket;
    private String name;
    private final BlockingQueue<String> sender;
    private final AtomicBoolean running;

    public OutputHandler(Socket socket, String name, BlockingQueue<String> sender, AtomicBoolean running){
      this.socket = socket;
      this.name = name;
      this.sender = sender;
      this.running = running;
    }
    public void run(){
      try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
        sender.put(name);
        while (running.get()) {
          if (br.ready()) {
            String clientInput = br.readLine();
            if (clientInput == null) break;
            sender.put("[" + name + "] " + clientInput);
            if (clientInput.equalsIgnoreCase("/quit")) {
              sender.put("/quit");
              running.set(false);
              break;
            }
          } else {
            if (socket.isClosed() || !running.get()) break;
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
          }
        }
      } catch (IOException | InterruptedException e) {
        e.printStackTrace();
      }
    }
  }
  static class ClientFileServer implements Runnable{
    private ServerSocket clientServerSocket;
    private final ConcurrentHashMap<String, String> fileMap;

    public ClientFileServer(ServerSocket clientServerSocket, ConcurrentHashMap<String, String> fileMap){
      this.clientServerSocket = clientServerSocket;
      this.fileMap = fileMap;
    }
    public void run(){
      try (Socket s = clientServerSocket.accept();
          BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
          BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {

          String name = in.readLine();
          String path = fileMap.get(name);

          if (path != null) {
              File file = new File(path);
              out.write((file.getName() + "\n").getBytes(StandardCharsets.UTF_8));
              out.flush();

              try (FileInputStream fis = new FileInputStream(file)) {
                  byte[] buf = new byte[64 * 1024];
                  int n;
                  while ((n = fis.read(buf)) != -1) {
                      out.write(buf, 0, n);
                  }
                  out.flush();
              }
              fileMap.remove(name);
          }
           
      } catch (IOException e) {
          System.err.println("File send error: " + e.getMessage());
      }
      
    }
  }
  static class FileClient implements Runnable {
    private String fileServerHostname;
    private int fileServerPort;
    private String name;

    public FileClient(String fileServerHostname, int fileServerPort, String name){
      this.fileServerHostname = fileServerHostname;
      this.fileServerPort = fileServerPort;
      this.name = name;
    }
    public void run(){
      try (Socket socket = new Socket(fileServerHostname, fileServerPort);
          PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
          BufferedInputStream in = new BufferedInputStream(socket.getInputStream())) {

          out.println(name);
          StringBuilder sb = new StringBuilder();
          int b;
          long totalBytes = 0;
          while ((b = in.read()) != '\n') sb.append((char)b);
          String filename = sb.toString().trim();

          try (FileOutputStream fos = new FileOutputStream(filename)) {
              byte[] buf = new byte[64 * 1024];
              int n;
              while ((n = in.read(buf)) != -1) {
                  fos.write(buf, 0, n);
                  totalBytes += n;
              }
              fos.getFD().sync();
          }
          if (totalBytes != 0 ){
            System.out.println("File Received");
          }
      } catch (IOException e) {
          System.err.println("Receive error: " + e.getMessage());
      }
    }
  }
  static class QueueSender implements Runnable {
    private final Socket socket;
    private final BlockingQueue<String> sender;

    public QueueSender(Socket socket, BlockingQueue<String> sender){
      this.socket = socket;
      this.sender = sender;
    }
    public void run(){
      boolean check = true;
      PrintWriter out;
      try {
        out = new PrintWriter(socket.getOutputStream(), true);
        while(check){
          try{
            String input = sender.take();
            if(input.equals("/quit")) {
              check = false;
              continue;
            }
            out.println(input);
          } catch(InterruptedException e){
            System.err.println("Error message: " + e);
          }
        }
      } catch (IOException e){
        System.err.println("Could not create PrintWriter to Server");
      }
    }
  }
}

