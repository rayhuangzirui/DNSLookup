package ca.ubc.cs.cs317.dnslookup;


import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;

import static ca.ubc.cs.cs317.dnslookup.DNSLookupService.DEFAULT_DNS_PORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class LocalNameServer {
    private final InetAddress address;
    private DatagramSocket socket;
    private final ExpectedQuery[] queries;
    private Thread thread;

    public LocalNameServer(InetAddress address, ExpectedQuery... queries) {
        this.address = address;
        this.queries = queries;
        try {
            startup();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    private void startup() throws SocketException {
        socket = new DatagramSocket(DEFAULT_DNS_PORT, address);
        this.thread = new Thread(this::body);
        this.thread.start();
    }

    private ExpectedQuery lookup(DNSQuestion question) {
        for (ExpectedQuery eq : queries) {
            if (eq.question.equals(question)) {
                return eq;
            }
        }
        return null;
    }

    private void body() {
        XDNSMessage reply;

        while (true) {
            try {
                AtomicReference<SocketAddress> address = new AtomicReference<>();
                XDNSMessage request = receiveMessage(socket, address);
                int requestID = request.getID();
                int qd = request.getQDCount();
                int nrrs = request.getANCount() + request.getNSCount() + request.getARCount();
                assertEquals(1, qd, "Expected one question, but found " + qd);
                assertEquals(0, nrrs, "Expected no resource records, but found " + nrrs);
                DNSQuestion question = request.getQuestion();
                ExpectedQuery query = lookup(question);
                assertNotNull(query, "Received a question that has no answer");
                reply = new XDNSMessage((short) requestID);
                reply.setQR(true);
                reply.setRcode(0);
                reply.setAA(false);
                reply.addQuestion(question);
                // Add the answers
                for (ResourceRecord rr : query.answers) {
                    reply.addResourceRecord(rr, "answer");
                    reply.setAA(true);
                }
                // Add the name servers
                for (ResourceRecord rr : query.nameservers) {
                    reply.addResourceRecord(rr, "nameserver");
                }
                // Add the additional records
                for (ResourceRecord rr : query.additional) {
                    reply.addResourceRecord(rr, "additional");
                }
                sendMessage(reply, socket, address.get());
            } catch (IOException e) {
                break;
            }
        }
    }

    public void shutdown() {
        socket.close();
        this.thread.interrupt();
        try {
            this.thread.join();
        } catch (InterruptedException ignored) {

        }
    }
    public static void sendMessage(XDNSMessage message, DatagramSocket socket, SocketAddress dest) throws IOException {
        byte[] response = message.getUsed();
        socket.send(new DatagramPacket(response, response.length, dest));
    }

    public static XDNSMessage receiveMessage(DatagramSocket socket, AtomicReference<SocketAddress> sender) throws IOException {
        byte[] bytes = new byte[DNSMessage.MAX_DNS_MESSAGE_LENGTH];
        DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
        socket.receive(packet);
        if (sender != null) sender.set(packet.getSocketAddress());
        return new XDNSMessage(bytes, packet.getLength());
    }

    public static XDNSMessage receiveMessage(DatagramSocket socket) throws IOException {
        return receiveMessage(socket, null);
    }
}
