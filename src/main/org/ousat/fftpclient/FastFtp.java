package org.ousat.fftpclient;

import java.io.*;
import java.net.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

import org.ousat.fftpclient.utils.TxQueue;
import org.ousat.fftpclient.utils.Segment;

/**
 * An implementation of a "FastFTP" client, which is capable of sending a file to a FastFTP server.
 * It uses unreliable UDP datagrams for file transfer but implements a version of Go-back-N protocol
 * to achieve reliable end-to-end transfer.
 *
 * @author Behnam Ousat
 * @version 1.0
 */
public class FastFtp {
    static Logger logger = Logger.getLogger( FastFtp.class.getName() );

    private final DatagramSocket datagramSocketSock = new DatagramSocket();
    private final int localPort = datagramSocketSock.getLocalPort();
    private final long timeOut;
    private final TxQueue txQueue;
    private InetSocketAddress serverSocketAddress;
    private Timer timer = new Timer( true );

    public FastFtp( int windowSize, long timeOut ) throws SocketException {
        this.timeOut = timeOut;
        this.txQueue = new TxQueue( windowSize );
    }

    /**
     * A driver for fast ftp client
     *
     * @param args arguments in order: fileName serverName serverHandshakePort windowSize timeOut
     */
    public static void main( String[] args ) {
        System.out.println("Hey!");
        try {
            String fileName = args[ 0 ];
            String serverName = args[ 1 ];
            int handShakePort = Integer.parseInt( args[ 2 ] );
            int windowSize = Integer.parseInt( args[ 3 ] );
            int timeOut = Integer.parseInt( args[ 4 ] );
            FastFtp fftp = new FastFtp( windowSize, timeOut );
            fftp.send( serverName, handShakePort, fileName );
        } catch ( NumberFormatException ex ) {
            logger.warning( "Something wrong with the arguments. Error: " + ex.getClass().getSimpleName() +
                    " - " + ex.getMessage() );
        } catch ( Exception ex ) {
            logger.warning( "Error occurred during file transmission. Error: " + ex.getClass().getSimpleName() +
                    " - " + ex.getMessage() );
        }
    }

    /**
     * Handshake with server and get the address to use for file transmission
     *
     * @param serverName The remote server that will receive the file
     * @param serverPort The port server is listening on for handshake
     * @param file       The file that is to be sent to the server
     *
     * @return An {@link InetSocketAddress} object that represents the socket address on which the
     * server expects UDP packets for the given file
     * @throws IOException If handshake fails due to I/O errors
     */
    private InetSocketAddress getServerSocketAddress( String serverName, int serverPort, File file ) throws IOException {

        InetAddress serverAddress = InetAddress.getByName( serverName );

        // connect and initialize input and output streams
        Socket handShakeSocket = new Socket();
        handShakeSocket.connect( new InetSocketAddress( serverAddress, serverPort ) );
        DataOutputStream handShakeOut = new DataOutputStream( handShakeSocket.getOutputStream() );
        DataInputStream handShakeIn = new DataInputStream( handShakeSocket.getInputStream() );

        // write file name, file size, and local UDP port info to handshake output
        handShakeOut.writeUTF( file.getName() );
        handShakeOut.writeLong( file.length() );
        handShakeOut.writeInt( localPort );
        handShakeOut.flush();

        // read remote UDP port from handshake input
        int dstPort = handShakeIn.readInt();

        // cleanup after handshake
        handShakeOut.close();
        handShakeIn.close();
        handShakeSocket.close();
        InetSocketAddress socketAddress = new InetSocketAddress( serverAddress, dstPort );
        logger.info( "Handshake done. Server ready to receive file: " + file.getName() +
                " on address: " + socketAddress );
        return socketAddress;
    }

    /**
     * Sends a file with given name to a remote fftp server.
     *
     * @param serverName The remote server that will receive the file
     * @param serverPort The port server is listening on for handshake
     * @param fileName   The file that is to be sent to the server
     *
     * @throws IOException          if send fails due to I/O errors
     * @throws InterruptedException if thread is interrupted while adding to tx queue
     */
    public void send( String serverName, int serverPort, String fileName ) throws IOException, InterruptedException {
        logger.info( "Sending file: " + fileName + " to:" + serverName );
        // prepare the file to be sent, set the server address and connect the UDP
        // socket to it.
        File file = new File( fileName );
        serverSocketAddress = getServerSocketAddress( serverName, serverPort, file );
        datagramSocketSock.connect( serverSocketAddress );

        // the input stream to read from file
        BufferedInputStream input = new BufferedInputStream( new FileInputStream( fileName ) );

        // initialize buffers  and variables for reading and tracking sent segments
        byte[] inBytes = new byte[ Segment.MAX_PAYLOAD_SIZE ];
        int bytesRead;
        int seqNum = 0;
        long totalSequences = getNeededSegments( file.length() );

        // create and start the ack-receiving thread
        AckReceiverThread ackReceiverThread = new AckReceiverThread( totalSequences );
        ackReceiverThread.start();

        // keep reading from file and when the queue has space, send segments and queue
        // them for acknowledgement
        while ( ( bytesRead = input.read( inBytes ) ) != -1 ) {
            byte[] payload = new byte[ bytesRead ];
            System.arraycopy( inBytes, 0, payload, 0, bytesRead );
            Segment segment = new Segment( seqNum, payload );
            while ( txQueue.isFull() ) {
                Thread.yield();
            }
            processSend( segment );
            seqNum++;
        }
        input.close();
        logger.info( "Finished reading the file. All segments have been queued." );
    }

    /**
     * Determines how many total segments need to be transmitted to completely transfer file
     *
     * @param fileLength length of the file in bytes
     *
     * @return total number of segments required
     */
    private long getNeededSegments( long fileLength  ) {
        return (long) Math.ceil( (double) fileLength / Segment.MAX_PAYLOAD_SIZE );
    }

    /**
     * Sends a segment to server, adds it to transmitted queue to be ACKed
     *
     * @param segment segment to be sent
     *
     * @throws IOException          if send fails due to I/O errors
     * @throws InterruptedException if thread is interrupted while adding to tx queue
     */
    private synchronized void processSend( Segment segment ) throws IOException, InterruptedException {
        sendSegment( segment );
        txQueue.add( segment );
        logger.fine( "Sent segment:" + segment.getSeqNum() + " and added to TXQueue." );
        if ( txQueue.size() == 1 ) {
            logger.fine( "Queue has only 1 item. Scheduling timer ... " );
            timer.schedule( new TimeOutHandler(), timeOut );
        }
    }

    /**
     * Sends a segment to the server over the datagram socket
     *
     * @param segment segment to send
     *
     * @throws IOException if send fails due to I/O errors
     */
    private void sendSegment( Segment segment ) throws IOException {
        logger.fine( "Sending segment :" + segment.getSeqNum() );
        DatagramPacket packet = new DatagramPacket( segment.getBytes(), segment.getBytes().length, serverSocketAddress );
        datagramSocketSock.send( packet );
    }

    /**
     * Processes an ACK segment, and removes ACKed segments from the transmission queue.
     *
     * @param ackSegment     an ACK segment for processing
     * @param finalSeqNumber the final sequence number we expect.
     *
     * @return true if the final ACK has been received, false otherwise
     * @throws InterruptedException if thread is interrupted while removing from tx queue
     */
    private synchronized boolean processAck( Segment ackSegment, long finalSeqNumber ) throws InterruptedException {
        logger.fine(
                "Processing ACK:" + ackSegment.getSeqNum() + ", TXQueue:" + txQueue );
        timer.cancel();
        if ( ackSegment.getSeqNum() >= finalSeqNumber ) {
            return true;
        }
        timer = new Timer( true );
        while ( txQueue.element() != null && txQueue.element().getSeqNum() < ackSegment.getSeqNum() ) {
            txQueue.remove();
        }
        if ( !txQueue.isEmpty() ) {
            timer.schedule( new TimeOutHandler(), timeOut );
        }
        return false;
    }

    /**
     * A thread that listens on the UDP socket for incoming ACKs from the server and processes them
     */
    private class AckReceiverThread extends Thread {
        private final long finalAckNumber;

        /**
         * @param finalAckNumber the final ack number that indicates transmission is done
         */
        public AckReceiverThread( long finalAckNumber ) {
            this.finalAckNumber = finalAckNumber;
        }

        public void run() {
            // a datagram packet that will be populated with a received segment
            byte[] rxBytes = new byte[ Segment.MAX_SEGMENT_SIZE ];
            DatagramPacket rxPacket = new DatagramPacket( rxBytes, rxBytes.length );

            // keep reading and process received datagrams
            while ( true ) {
                try {
                    datagramSocketSock.receive( rxPacket );
                    Segment rxSegment = new Segment( rxPacket.getData() );
                    boolean done = processAck( rxSegment, finalAckNumber );
                    if ( done ) {
                        logger.fine( "Final ACK received." );
                        break;
                    }
                } catch ( IOException | InterruptedException ex ) {
                    logger.warning( "Error occurred in ACK reception. Error: " + ex.getMessage() );
                }
            }
            // Clean up. All expected ACKs received
            datagramSocketSock.close();
            logger.info( "All expected ACKs have been received. Transmission complete." );
        }
    }

    /**
     * Handles the tasks needed to be done after time-outs
     */
    private class TimeOutHandler extends TimerTask {
        /**
         * Re-send all segments in tx queue and reset the timer unless queue is empty
         */
        public synchronized void run() {
            try {
                logger.fine( "Time-out reached. Resending everything in TXQueue..." );
                for ( Segment seg : txQueue.toArray() ) {
                    sendSegment( seg );
                }
                if ( !txQueue.isEmpty() ) {
                    timer = new Timer( true );
                    timer.schedule( new TimeOutHandler(), timeOut );
                }
            } catch ( IOException ex ) {
                logger.warning( "Error occurred when handling timeout. Error: " + ex.getMessage() );
            }
        }
    }
}
