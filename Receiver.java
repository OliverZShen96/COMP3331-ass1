import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.Random;

public class Receiver {
	
	final static byte SYN = 0x1; // 00000001
	final static byte ACK = 0x2; // 00000010
	final static byte FIN = 0x4; // 00000100
	final static int HEADER_SIZE = 9;
	final static int BUFFER_SIZE = 100;
	
	private static DatagramSocket socket;
	private static DatagramPacket packetIn;
	private static DatagramPacket packetOut;
	
	private static int currentAckNum;
	private static int currentSeqNum;
	private static int initialAckNum;
	private static byte[][] fileDataBuffer;
	private static String filename;
	private static Timer timer;
	private static PrintWriter receiverLog;
	private static int receivedPacketCounter;
	private static int receivedByteCounter;
	
	public static void main(String[] args) throws IOException {
		// Check if arguments are correct
		if (args.length != 2) {
			System.out.println("Required arguments: receiver_port file.txt");
			return;
		}
		
		receiverLog = new PrintWriter("Receiver_log.txt", "UTF-8");
		
		// Process arguments
		int receiver_port = Integer.parseInt(args[0]);
		filename = args[1];
		socket = new DatagramSocket(receiver_port, InetAddress.getByName(InetAddress.getLocalHost().getHostAddress()));
		
		// Initialize things to be used later
		receivedPacketCounter = 0;
		receivedByteCounter = 0;
		packetIn = new DatagramPacket(new byte[1024], 1024);
		packetOut = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);
		timer = new Timer();
		timer.start();
		
		handShake();

		fileDataBuffer = new byte[BUFFER_SIZE][];
		
		while (true) {
			// receive packet
			socket.receive(packetIn);
			printPacketData("rcv", packetIn);
			
			// Update Acknowledgement and Sequence numbers
			currentAckNum = getPacketSeqNum(packetIn) + packetIn.getLength() - HEADER_SIZE;
			currentSeqNum += 1;
			
			if (packetIn.getData()[0] == FIN) {
				break;
			} else {
				receivedPacketCounter ++;
				receivedByteCounter += (packetIn.getLength() - HEADER_SIZE);
				
				byte[] data = packetIn.getData();
				
				// write out data to buffer
				byte[] copyData = Arrays.copyOf(data, packetIn.getLength());
				fileDataBuffer[(getPacketSeqNum(packetIn)-initialAckNum)/(packetIn.getLength()-HEADER_SIZE)] = copyData;
				
				//// Acknowledge received packet
				// Fill ACK packet with data
				data = new byte[HEADER_SIZE];
				Arrays.fill(data, (byte) 0);
				data[0] = ACK;
				System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
				System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
				packetOut.setData(data, 0, data.length);
				
				// Send ACK packet
				socket.send(packetOut);	
				printPacketData("snd", packetOut);
				
				}
		}
		
		// Close connection
		terminateConnection();
		// Write transferred data to file
		writeFile();
		
		// Write final log lines
		receiverLog.println("Data Segments received: " + receivedPacketCounter);
		receiverLog.println("Total Bytes received: " + receivedByteCounter);
		receiverLog.println("Duplicate segments received: 0");
		// Close log writer
		receiverLog.close();
		// Stop the timer
		timer.kill();
	}
	
	private static void handShake() throws IOException {
		currentSeqNum = new Random().nextInt(10000);
		
		///////////////////////////////// RECEIVE SYN
		while (packetIn.getData()[0] != SYN) {
			socket.receive(packetIn);
		}
		// configure outgoing packet's destination address and port to match the address of incoming packet
		packetOut.setAddress(packetIn.getAddress());
		packetOut.setPort(packetIn.getPort());
		// update Acknowledgement Number based on incoming packet Sequence Number
		currentAckNum = 1 + getPacketSeqNum(packetIn);
		
		// Write packet data to log
		printPacketData("rcv", packetIn);
		
		///////////////////////////////// SEND SYN ACK
		// create byte array for outgoing handshake packet
		byte[] data = new byte[HEADER_SIZE];
		data[0] = SYN|ACK;
		// Add Sequence and Acknowledgement numbers to data
		System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
		// Add data to packet
		packetOut.setData(data, 0, data.length);
		// Send packet
		socket.send(packetOut);
		
		// Write packet data to log
		printPacketData("snd", packetOut);
		
		///////////////////////////////// RECEIVE ACK
		// receive ACK
		while (packetIn.getData()[0] != ACK) {
			socket.receive(packetIn);
		}
		
		// Write packet data to log
		printPacketData("rcv", packetIn);
		
		// Store starting Acknowledgement number for calculating things such as bytes sent
		initialAckNum = currentAckNum;
	}
	
	private static void terminateConnection() throws IOException {
		
		// prepare byte array for termination packets
		byte[] data = new byte[HEADER_SIZE];
		// Send FIN ACK
		data[0] = (FIN|ACK);
		System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
		packetOut.setData(data, 0, data.length);
		socket.send(packetOut);
		printPacketData("snd", packetOut);
		
		// Send FIN
		data[0] = (FIN);
		System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
		packetOut.setData(data, 0, data.length);
		socket.send(packetOut);
		printPacketData("snd", packetOut);
		
		// Receive FIN ACK
		while (packetIn.getData()[0] != (FIN|ACK)) {
			socket.receive(packetIn);
		}
		printPacketData("rcv", packetIn);
		
		// Close the socket
		socket.close();

	}
	
	// Helper method to translate an int to a byte array
	private static byte[] intToByteArray( int num ) {
		byte[] result = new byte[4];
		result[0] = (byte) ((num & 0xFF000000) >> 24);
		result[1] = (byte) ((num & 0x00FF0000) >> 16);
		result[2] = (byte) ((num & 0x0000FF00) >> 8);
		result[3] = (byte) ((num & 0x000000FF) >> 0);
		return result;
	}
	
	// Helper method to get a packet's sequence number
	// Extracts bytes 1-4 of its data, and arranges it into an int
	private static int getPacketSeqNum(DatagramPacket p) {
		int result = ((p.getData()[1] & 0xFF) << 24)
				| ((p.getData()[2] & 0xFF) << 16)
		        | ((p.getData()[3] & 0xFF) << 8)
		        | (p.getData()[4] & 0xFF);
		return result;
	}
	
	// Helper method to get a packet's acknowledgement number
	// Extracts bytes 5-8 of its data, and arranges it into an int
	private static int getPacketAckNum(DatagramPacket p) {
		int result = ((p.getData()[5] & 0xFF) << 24)
				| ((p.getData()[6] & 0xFF) << 16)
		        | ((p.getData()[7] & 0xFF) << 8)
		        | (p.getData()[8] & 0xFF);
		return result;
	}
	
	// Writes all the data in the buffer out to the file
	private static void writeFile() throws FileNotFoundException, UnsupportedEncodingException {
		PrintWriter writer = new PrintWriter(filename, "UTF-8");
		for (byte[] segmentData: fileDataBuffer) {
			if (segmentData == null) break;
			for (int i = HEADER_SIZE; i < segmentData.length ; i++) {
				if (segmentData[i] == 0) break;
				writer.print((char)segmentData[i]);
			}
		}
		writer.close();
	}
	
	// Prints the packet's data to the receiver log
	// Needs to know whether the packet is incoming, outgoing, or dropped
	private static void printPacketData(String packetStatus, DatagramPacket p) {
		receiverLog.print(packetStatus);
		receiverLog.print(" " + String.format("%6s", timer.getTimeElapsed()));
		
		if (p.getData()[0] == 0) receiverLog.print("  D ");
		if (p.getData()[0] == ACK) receiverLog.print("  A ");
		if (p.getData()[0] == SYN) receiverLog.print("  S ");
		if (p.getData()[0] == (SYN|ACK)) receiverLog.print(" SA ");
		if (p.getData()[0] == FIN) receiverLog.print("  F ");
		if (p.getData()[0] == (FIN|ACK)) receiverLog.print(" FA ");
		
		receiverLog.print(String.format("%5s", getPacketSeqNum(p)));
		receiverLog.print(" " + String.format("%4s",(p.getLength() - HEADER_SIZE)));
		receiverLog.println(" " + String.format("%5s", getPacketAckNum(p)));
	}
}
