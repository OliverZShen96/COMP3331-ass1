import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Random;

public class Sender {
	
	final static byte SYN = 0x1; // 00000001
	final static byte ACK = 0x2; // 00000010
	final static byte FIN = 0x4; // 00000100
	final static int HEADER_SIZE = 9;
	
	private static DatagramPacket packetOut;
	private static DatagramPacket packetIn;
	private static int MWS;
	private static int MSS;
	private static int timeout;
	private static String filepath;
	private static int receiverPort;
	private static InetAddress receiverHostIP;
	private static DatagramSocket socket;
	private static double pdrop;
	private static int seed;
	
	private static int currentAckNum;
	private static int currentSeqNum;
	private static int initialSeqNum;
	private static Random r;
	private static PrintWriter senderLog;
	
	private static int bytesTransferredCounter;
	private static int segmentsSentCounter;
	private static int droppedPacketCounter;
	private static int retransmittedSegmentCounter;
	
	private static Timer timer;
	
	public static void main(String[] args) throws IOException {
		if (args.length != 8) {
			System.out.println("Required arguments: receiver_host_ip receiver_port file.txt MWS MSS timeout pdrop seed");
			return;
		}
		
		// Set up parameters inside fields
		receiverHostIP = InetAddress.getByName(args[0]);
		receiverPort = Integer.parseInt(args[1]);
		filepath = args[2];
		MWS = Integer.parseInt(args[3]);
		MSS = Integer.parseInt(args[4]);
		timeout = Integer.parseInt(args[5]);
		pdrop = Double.parseDouble(args[6]);
		r = new Random(seed);
		packetOut = new DatagramPacket(new byte[1024], 1024);
		packetIn = new DatagramPacket(new byte[1024], 1024);
		
		// Set up Sender Log Writer
		senderLog = new PrintWriter("Sender_log.txt", "UTF-8");
		
		// Sets up UDP socket
		socket = new DatagramSocket();
		socket.setSoTimeout(timeout);
		
		// Set up Packet's address
		packetOut.setAddress(receiverHostIP);
		packetOut.setPort(receiverPort);
		
		// Initialize timer
		timer = new Timer();
		timer.start();
		
		// Initialize counters (used for log)
		bytesTransferredCounter = 0;
		segmentsSentCounter = 0;
		droppedPacketCounter = 0;
		retransmittedSegmentCounter = 0;
		
		// Initate handshake
		handShake();
		
		// process file
		File file = new File(filepath);
		InputStream in = new FileInputStream(file);
		
		// create packet's byte array
		byte[] data = new byte[MSS + HEADER_SIZE];
		
		int unAckedBytes = 0;
		LinkedList<DatagramPacket> unAckedPackets = new LinkedList<DatagramPacket>();
		
		while (true) {
			// If there is room in the window for packets, create and send them
			if (unAckedBytes < MWS && file.length() > currentSeqNum - initialSeqNum) {
				
				// Fill data array with required data (sequence/acknowledgement numbers, then file data)
				Arrays.fill(data, (byte) 0);
				System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
				System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
				in.read(data, HEADER_SIZE, MSS);
				
				// Put the data in the packet
				packetOut.setData(data);
				
				// Decide if packet is dropped or sent
				// Whether the packet is dropped or sent, the packet data is written out to the log
				segmentsSentCounter++;
				bytesTransferredCounter += (packetOut.getLength() - HEADER_SIZE);
				if (r.nextDouble() > pdrop) {
					printPacketData("snd", packetOut);
					socket.send(packetOut);
				} else {
					droppedPacketCounter++;
					printPacketData("drp", packetIn);
				}
				
				// Update Sequence number and unacknowledged byte counter
				currentSeqNum += (packetOut.getLength() - HEADER_SIZE);
				unAckedBytes += (packetOut.getLength() - HEADER_SIZE);

				// Store copy of packet in queue
				byte[] copyData = Arrays.copyOf(data, MSS+HEADER_SIZE);
				DatagramPacket copy = new DatagramPacket(copyData, copyData.length);
				unAckedPackets.add(copy);
				
				// Continue looping
				continue;
			}
			
			// If there are no packets to send, wait for ACKs
			try {
				socket.receive(packetIn);
				
				// Update Acknowledgement number based on incoming packet sequence number
				currentAckNum = getPacketSeqNum(packetIn) + 1;
				// Write received packet data out to log
				printPacketData("rcv", packetIn);
				
				// If the packet received is an ACK (which it should be),
				// Remove the corresponding packet from the unacknowledged packet list
				if (packetIn.getData()[0] == ACK) {
					DatagramPacket toRemove = null;
					for (DatagramPacket packet: unAckedPackets) {
						if (getPacketSeqNum(packet) + packet.getLength() - HEADER_SIZE == getPacketAckNum(packetIn)) {
							toRemove = packet;
							break;
						}
					}
					unAckedPackets.remove(toRemove);
					unAckedBytes -= (toRemove.getLength() - HEADER_SIZE);
				}
				// If there are no unacknowledged packets, and all of the file has been read, finish looping
				if (unAckedPackets.isEmpty() && file.length() <= currentSeqNum - initialSeqNum) {
					in.close();
					break;
				}
				
			// If time spent waiting for ACKs exceeds timeout value, re-send the oldest unacknowledged packet
			} catch (SocketTimeoutException e) {
				retransmittedSegmentCounter++;
				DatagramPacket resend = unAckedPackets.getFirst();
				resend.setAddress(receiverHostIP);
				resend.setPort(receiverPort);
				socket.send(resend);
				printPacketData("snd", resend);
		    }
		}	
		
		// Close the connection
		terminateConnection();	
		// Writer final log lines
		senderLog.println("Original bytes transferred: " + bytesTransferredCounter);
		senderLog.println("Data Segments Sent (excluding retransmissions): " + segmentsSentCounter);
		senderLog.println("Packets Dropped: " + droppedPacketCounter);
		senderLog.println("Retransmitted Segments: " + retransmittedSegmentCounter);
		senderLog.println("Duplicate Acknowledgements receieved: 0");
		// Close the log Writer
		senderLog.close();
		// Stop the timer
		timer.kill();
	}
	
	private static void handShake() throws IOException {
		
		// Generate random starting sequence number between 0 and 10000
		currentSeqNum = r.nextInt(10000);
		
		// Prepare byte array for handshake packets
		byte[] data = new byte[HEADER_SIZE];
		
		///////////////////////////////// SEND SYN
		// SYN flag
		data[0] = SYN;
		// Sequence and Acknowledgement numbers
		System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
		// put the data in the packet
		packetOut.setData(data, 0, data.length);
		// Send the packet
		socket.send(packetOut);
		// Increment Sequence number
		currentSeqNum += 1;

		// Write packet data to log
		printPacketData("snd", packetOut);
		
		///////////////////////////////// RECEIVE SYN ACK
		// Receive SYN ACK
		while (packetIn.getData()[0] != (SYN|ACK)) {
			socket.receive(packetIn);
		}
		printPacketData("rcv", packetIn);
		// Update Sequence and Acknowledgement Numbers
		currentAckNum = getPacketSeqNum(packetIn)+1;
		currentSeqNum = getPacketAckNum(packetIn);	
		initialSeqNum = currentSeqNum;

		///////////////////////////////// SEND ACK
		// ACK flag
		data[0] = ACK;
		// Sequence and Acknowledgement numbers
		System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
		// put the data in the packet
		packetOut.setData(data, 0, data.length);
		// Send the packet
		socket.send(packetOut);
		
		// Write packet data to log
		printPacketData("snd", packetOut);
	}
	
	private static void terminateConnection() throws IOException {
		
		// Prepare byte array for termination packets
		byte[] data = new byte[HEADER_SIZE];
		
		// Send FIN
		data[0] = FIN;
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
		
		// Receive FIN
		while (packetIn.getData()[0] != (FIN)) {
			socket.receive(packetIn);
		}
		printPacketData("rcv", packetIn);
		
		// Send FIN ACK
		data[0] = (FIN|ACK);
		System.arraycopy(intToByteArray(currentSeqNum), 0, data, 1, 4);
		System.arraycopy(intToByteArray(currentAckNum), 0, data, 5, 4);
		packetOut.setData(data, 0, data.length);
		socket.send(packetOut);
		printPacketData("snd", packetOut);
		
		// close the socket
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
	
	// Prints the packet's data to the sender log
	// Needs to know whether the packet is incoming, outgoing, or dropped
	private static void printPacketData(String packetStatus, DatagramPacket p) {
		senderLog.print(packetStatus);
		senderLog.print(" " + String.format("%6s", timer.getTimeElapsed()));
		
		if (p.getData()[0] == 0) senderLog.print("  D ");
		if (p.getData()[0] == ACK) senderLog.print("  A ");
		if (p.getData()[0] == SYN) senderLog.print("  S ");
		if (p.getData()[0] == (SYN|ACK)) senderLog.print(" SA ");
		if (p.getData()[0] == FIN) senderLog.print("  F ");
		if (p.getData()[0] == (FIN|ACK)) senderLog.print(" FA ");
		
		senderLog.print(String.format("%5s", getPacketSeqNum(p)));
		senderLog.print(" " + String.format("%4s",(p.getLength() - HEADER_SIZE)));
		senderLog.println(" " + String.format("%5s", getPacketAckNum(p)));
	}
}

