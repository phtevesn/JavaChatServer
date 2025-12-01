package ChatServer;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.concurrent.*;

/*
  Client List Management: Ensuring clients are added and removed safely.
  Message Broadcasting: Preventing inconsistencies when multiple threads send messages.
  File Transfer Coordination: Avoiding conflicts when multiple transfers occur simultaneously.
 */
public class tcpcss{
  private final static String leftMessage = " has left the chat";
  private final static String joinMessage = "has joined the chat.";
  public static void main(String[] args){
    int port = 12345;
    final BlockingQueue<String> q = new LinkedBlockingQueue<>();
    final ConcurrentHashMap<String, PrintWriter> map = new ConcurrentHashMap<>();
    //Map below stores the name of user and the ip and port in a string form 

    //name and writer to iterate through 
    if (args.length == 1) {
      try {
        port = Integer.parseInt(args[0]);
      } catch (NumberFormatException e){
        System.err.println("Invalid port number");
      }
    }

    try{
      ServerSocket serverSocket = new ServerSocket(port);
      System.out.println("Listening on port: " + serverSocket.getLocalPort());
      System.out.println("Waiting for connections...");

      Thread inputThread = new Thread(new InputHandler(q, map));
      inputThread.start();

      while (true){
        Socket clientSocket = serverSocket.accept();
        InetAddress clientAddr = clientSocket.getInetAddress();
        int clientPort = clientSocket.getPort();
        System.out.println("New Connection, thread name is Thread-" + map.size() + " ip is " + 
          clientAddr.getHostAddress() + " port is " + clientPort);
      
        Thread clientThread = new Thread(new ClientHandler(clientSocket, q, map));
        clientThread.start();
      }

    } catch (IOException e){
      System.err.println("Could not create server socket");
    }
  }

  static class ClientHandler implements Runnable{  
    private final Socket socket;
    private final BlockingQueue<String> q;
    private final ConcurrentHashMap<String, PrintWriter> map;

    public ClientHandler(Socket socket, BlockingQueue<String> q, ConcurrentHashMap<String, PrintWriter> map){
      this.socket = socket;
      this.q = q;
      this.map = map; 
    }

    public void run(){
      try{
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        String name = in.readLine();

        if(map.containsKey(name)){
          out.println("Username " + name + " is currently being used please join with a different name");
          socket.close();
          return;
        }
        map.put(name, out);

        try {
          q.put(name + " " + joinMessage);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        while (true){
          String input = in.readLine();
          if (input == null) {
            if(map.containsKey(name)) map.remove(name);
            break;
          }
          try{
            q.put(input);
          } catch (InterruptedException e){
            e.printStackTrace();;
          }
        }

      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        
        try {
          socket.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    }
  }

  static class InputHandler implements Runnable{
    private final BlockingQueue<String> q;
    private final ConcurrentHashMap<String, PrintWriter> map;

    public InputHandler(BlockingQueue<String> q, ConcurrentHashMap<String, PrintWriter> map){
      this.q = q;
      this.map = map;
    }
    public void run(){
      while(true){
        try {
          String input = q.take();
          //need to parse for commands
          if (!checkForCommand(input)){
            //server printing output
            System.out.println(input); 
            //send input to everyone else
            String nameOfInput = parseForName(input);
            for (Map.Entry<String,PrintWriter> entry : map.entrySet()){
              if (entry.getKey().equals(nameOfInput)) continue;
              entry.getValue().println(input);
            }
          }
          
        } catch (InterruptedException e){
          System.err.println("Blocking Queue Interrupted");
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    //false means not a command
    private boolean checkForCommand(String input){
      if (input.charAt(0) != '[') return false;
      int i;
      for (i = 0; i < input.length(); i++){
        if (input.charAt(i) == ']') break;
      }
      if (i >= input.length()-1)  return false;
      if (input.charAt(i+=2) != '/') return false;
      String name = parseForName(input);
      String inputSub = input.substring(i, input.length());
      return isValidCommand(inputSub, name);

    }
    private boolean isValidCommand(String input, String name){
      String command = "";
      int index;
      for (index = 0; index < input.length(); index++){
        char c = input.charAt(index);
        if(c == ' ') break;
        command = command + c;
      }
      if (command.equals("/quit") || command.equals("/who")) {
        Thread t = new Thread(new CommandHandler(q, map, command, name, name, "temp"));
        t.start();
        return true;
      } else if (command.equals("/sendfile")) {
        String recipient = parseForName(input.substring(index+=1, input.length()));
        int i;
        for (i = index + 1; i<input.length(); i++){
          if (input.charAt(i) == ' ') break;
        } 
        if (!map.containsKey(recipient)){
          map.get(name).println(recipient + " is not in the chat.");
          return true;
        } else {
          String filename = input.substring(i+=1, input.length());
          Thread t = new Thread(new CommandHandler(q, map, command, name, recipient, filename));
          t.start();
        }
        return true;
      } else if(command.equals("/acceptfile") || command.equals("/rejectfile") || command.equals("/address")) {
        String recipient = parseForName(input.substring(index+=1, input.length()));
        Thread t = new Thread(new CommandHandler(q, map, command, name, recipient, "temp"));
        t.start();
        return true;
      } 
      else {
        map.get(name).println("The command: " + input + " is not valid");
        return true;
      }

    }
    private String parseForName(String input){
      String name = "";
      char check = ' ';
      if (input.charAt(0) ==  '['){
        check = ']';
      }
      for (int i = 0; i<input.length(); i++){
        char c = input.charAt(i);
        if(c == '[') continue;
        if(c == check) break;
        name = name + c;
      }
      return name;
    }
  }
  static class CommandHandler implements Runnable {
    private final BlockingQueue<String> q;
    private final ConcurrentHashMap<String, PrintWriter> map;
    private final String command;
    private final String sender;
    private final String recipient;
    private final String filename;
    public CommandHandler(BlockingQueue<String> q, ConcurrentHashMap<String, PrintWriter> map,
    String c, String s, String r, String f){
      this.q = q;
      this.map = map;
      command = c;
      sender = s;
      recipient = r;
      filename = f;
    }

    public void run(){
      if(command.equals("/quit")){
        
        q.add(sender + leftMessage);
        map.get(sender).println("/quit");
        map.remove(sender);
      } else if (command.equals("/who")){
        System.out.println(sender + " requested online users.");
        String s = "Online Users: " + String.join(", ", map.keySet());
        map.get(sender).println(s);
      } else if (command.equals("/sendfile")){
        //send file will put this filesend of the queue of the sender
        q.add("Initiated file transfer from " + sender + " to " + recipient);
        map.get(sender).println("/send/" + recipient + "/" + filename);
      } else if (command.equals("/rejectfile")){
        q.add(recipient + " has rejected file transfer request from " + sender);
        //lets sender know to remove name from Concurrent map
        map.get(recipient).println("/reject/" + sender);
      } else if (command.equals("/acceptfile")){
        q.add(recipient + " has accepted file transfer request from " + sender);
        map.get(recipient).println("/accept/" + sender);
      } else if (command.equals("/address")){
        //sender = name of client w/ file server
        //recipient = address/port/recipientName
        String[] tokens = recipient.split("/");
        map.get(tokens[2]).println("/address/"+recipient);
      }      
    }
  }
}
