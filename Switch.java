import java.io.*;
import java.net.*;
import java.lang.Thread;
import java.util.*;
import java.lang.Math;

class NeighborInfo {
  public int switchID;
  public int port;
  public InetAddress address;
  public int alive;
  public Timer receive_timer;//record time out for each
  public Timer send_timer;//record the send gap for each 

  NeighborInfo(){
    this.switchID=-1;
    this.port=-1;
    this.alive=0;
    this.receive_timer=null;
    this.send_timer=null;
  }
}

public class Switch {
  private static int switchID;
  private static ArrayList<NeighborInfo> neighbor_list=new ArrayList<NeighborInfo>();
  private static DatagramSocket socket = null;
  private static int serverPort=5000;
  private static String serverHostname;
  private static int K=1000;
  private static int M=5;
  private static int fail_neighbor=-1;
  private static boolean quiet=true;
  private static Date date=new Date();
  private static HashMap<Integer, ArrayList<Integer>> map = new HashMap<Integer, ArrayList<Integer>>();
  
  public static void main (String[] args) {
    try {
      serverHostname = new String ("localhost");
      switchID = Integer.parseInt(args[0].trim());
      boolean has_fail_neighbor=false;
      for (String s: args){
        if (s.equals("-f")){
          has_fail_neighbor=true;
          continue;
        }
        else if(s.equals("-nq")){
          quiet=false;
          continue;
        }
        else if(has_fail_neighbor){
          fail_neighbor=Integer.parseInt(s);
          has_fail_neighbor=false;
        }
        else{
          serverHostname = args[1];
          serverPort=Integer.parseInt(args[2]);
        }
      }

      init();
      register_to_host();

      while(true){
        byte[] buffer = new byte[1024 * 64]; 
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);  
        socket.receive(packet);
        String str = new String(packet.getData()); 

        if(!quiet){
          date=new Date();
          System.out.println(date.toString()+" " + "received: "+ str);
        }

        String[] word = str.split(" ");

        if(word[0].equals("REGISTER_RESPONSE")){
          host_response(str);
        }
        else if(word[0].equals("REGISTER_RESPONSE_END")){
          date=new Date();
          System.out.println(date.toString()+" " + "register success");
          break;
        }
        else {
          continue;
        }
      }

      date=new Date();
      System.out.println(date.toString()+" " + "start listening");

      while(true){
        byte[] buffer = new byte[1024 * 64]; 
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);  
        socket.receive(packet);
        new Thread(new packet_handler(packet,quiet)).start();  
      }
    }
    catch (Exception e) {
      System.err.println("Exception caught:" + e);
    }
  }

  public static void init() throws SocketException{  
    try {
      socket = new DatagramSocket(0);  
      System.out.println("client " + switchID + " start success!");  
    } catch (Exception e) {  
      socket = null;  
      System.out.println("client " + switchID + " start fail!");
    }  
  }  

  public static void register_to_host(){
    try {   
      byte[] sendData  = new byte[1024]; 
      date=new Date();
      System.out.println (date.toString()+" " + "Sending register information to " + serverHostname + " on port " + serverPort);
      String request="REGISTER_REQUEST " + switchID;
      sendData = request.getBytes(); 
      DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(serverHostname), serverPort); 
      socket.send(sendPacket); 
      Timer timer = new Timer();
      timer.schedule(new Periodic_timer_task(2),0, K);
    } catch (Exception e) {  
      System.err.println("Exception caught in register_to_host:" + e);  
    }  
  }

  public static void handle_hashMap(HashMap<Integer, ArrayList<Integer>> map, String str) {
    String[] word = str.split(" ");
    int dest = Integer.parseInt(word[1].trim());
    int router = Integer.parseInt(word[2].trim());
    int band = Integer.parseInt(word[3].trim());
    int leg = Integer.parseInt(word[4].trim());

    if(!map.containsKey(dest)) {
      ArrayList<Integer> temp = new ArrayList<Integer>();
      temp.add(router);
      temp.add(band);
      temp.add(leg);
      map.put(dest, temp);
    } else {
      map.get(dest).clear();
      map.get(dest).add(router);
      map.get(dest).add(band);
      map.get(dest).add(leg);
    }

  }

  public static void host_response(String str){ //handle respones from host
    try{
      String[] word = str.split(" ");
      NeighborInfo neighbor= new NeighborInfo();
      neighbor.switchID=Integer.parseInt(word[1].trim());
      neighbor.alive=Integer.parseInt(word[2].trim());

      if(neighbor.alive!=0){
        neighbor.address=InetAddress.getByName(word[3]);
        neighbor.port=Integer.parseInt(word[4].trim());
        date=new Date();
        System.out.println(date.toString()+" switch "+ neighbor.switchID+ " is alive");
        Timer timer = new Timer();
        timer.schedule(new Periodic_timer_task(1,neighbor.address,neighbor.port,switchID),0, K);
        neighbor.send_timer=timer;
        Timer timer2=new Timer();
        timer2.schedule(new Periodic_timer_task(3,neighbor.switchID),M*K);
        neighbor.receive_timer=timer2;
      }
      neighbor_list.add(neighbor);
    }catch (Exception e){
      System.err.println("Exception caught in host_response:" + e);
    }
  }

  public static void alive_switch(int ID, InetAddress address, int port){ //handle alive neighbor
    try{
      for( NeighborInfo s : neighbor_list){
        if (s.switchID==ID && s.switchID!=fail_neighbor) {
          if(s.alive==0){
            s.alive=1;
            s.address=address;
            s.port=port;
            date=new Date();
            System.out.println(date.toString()+ " switch "+ s.switchID+ " is alive");
            Timer timer = new Timer();
            timer.schedule(new Periodic_timer_task(1,s.address,s.port,switchID),0, K);
            s.send_timer=timer;
            Timer timer2=new Timer();
            timer2.schedule(new Periodic_timer_task(3,ID),M*K);
            s.receive_timer=timer2;
            topology_update();
          }
          else{
            if(s.receive_timer!=null)
              s.receive_timer.cancel();
            Timer timer2=new Timer();
            timer2.schedule(new Periodic_timer_task(3,ID),M*K);
            s.receive_timer=timer2;
          }
        }
      }
    }catch (Exception e){
      System.err.println("Exception caught in alive_switch:" + e);
    }
  }

  public static void dead_switch(int ID){//handle time out neighbor 
    try{
      for(NeighborInfo s : neighbor_list){
        if (s.switchID==ID) {
          s.alive=0;
          s.send_timer.cancel();
          s.receive_timer.cancel();
          topology_update();
        }
      }

    }catch (Exception e){
      System.err.println("Exception caught:" + e);
    }
  }

  public static void SendString(String str, InetAddress address, int port){//send string to a certain dest
    try{
      byte[] sendData  = new byte[1024];
      sendData=str.getBytes();
      DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port); 
      socket.send(sendPacket);
    }
    catch (SocketException e) {
      System.out.println("send packet fail");
    }
    catch (IOException e){
      System.out.println("send packet fail");
    }
  }

  public static void topology_update(){
    try{
      String str="TOPOLOGY_UPDATE "+switchID;
      if(neighbor_list==null){
        System.out.println("error");
        return;
      }
      for(NeighborInfo s: neighbor_list){
        if(s.alive==1){
          str=str+" " + s.switchID;
        }
      }

      SendString(str, InetAddress.getByName(serverHostname),serverPort);
    } catch (Exception e){
      System.err.println("Exception caught in topology_update:" + e);
    }
  }

  public static void routing_update(String str){
    try{
      handle_hashMap(map, str);
      String[] word = str.split(" ");
      String dest = word[1].trim();
      String router = word[2].trim();
      String band = word[3].trim();
      String leg = word[4].trim();
      date=new Date();

      if (Integer.parseInt(band) == Integer.MAX_VALUE) band = "INF";
      else if (Integer.parseInt(band) == Integer.MIN_VALUE) band = "0";
      if (Integer.parseInt(leg) == Integer.MAX_VALUE) leg = "INF";
      System.out.print(date.toString()+"\n" + "new routing info\ndest\t\trouter\t\tband\t\tleg\n"+dest+"\t\t"+router+"\t\t"+band+"\t\t"+leg+"\n");
    
    }catch (Exception e){
      System.err.println("Exception caught in routing_update:" + e);
    }
  }
}


class Periodic_timer_task extends TimerTask { //handle all kinds of timer
  private InetAddress address;
  private int port;
  private int switchID;
  private int type; //3 for time out detection. 1 for KEEP_ALIVE; 2 for TOPOLOGY_UPDATE; 
  private int neighbor_ID; // record the ID of the neighbor
  private Date date;

  Periodic_timer_task (int type, InetAddress address, int port, int switchID){//for type 1
    this.type=type;
    this.address=address;
    this.port=port;
    this.switchID=switchID;
  }

  Periodic_timer_task (int type, int neighbor_ID){// for type 0
    this.type=type;
    this.neighbor_ID=neighbor_ID;
  }

  Periodic_timer_task (int type){ // for type 2
    this.type=type;
  }

  public void run() {
    try {
      if(type==1){
        String str="KEEP_ALIVE "+switchID;
        Switch.SendString(str,address,port);
      }
      else if(type==2){
        Switch.topology_update();
      }
      else if(type==3){
        date=new Date();
        System.out.println(date.toString()+" switch " + neighbor_ID +" is down");
        Switch.dead_switch(neighbor_ID);
      }

    } catch (Exception e) {
      System.err.println("Exception caught:" + e);
    }
  }
}

class packet_handler implements Runnable {  
  private DatagramPacket packet;  
  private boolean quiet;
  private Date date=new Date();
  
  packet_handler(DatagramPacket packet, boolean quiet){  
    this.packet = packet;  
    this.quiet=quiet;
  }  

  public void run() {  
    try {  
      String str = new String(packet.getData()); 

      InetAddress address = packet.getAddress(); 
      int port = packet.getPort();
      if(!quiet){
        System.out.println(date.toString()+" " + "received: "+ str);
      }
      
      String[] word = str.split(" ");
      if(word[0].equals("NEW_ROUTING_INFO")){
        Switch.routing_update(str);
      }
      else if(word[0].equals("KEEP_ALIVE")){
        Switch.alive_switch(Integer.parseInt(word[1].trim()), address, port);
      }

    } catch (Exception e) {  
      System.err.println("Exception caught:" + e);
    }  
  }  
}


