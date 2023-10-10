package ca.ubc.cs.cs317.dnslookup;

import java.io.*;
import java.net.*;
import java.sql.SQLOutput;
import java.util.*;
import java.util.stream.Collectors;

public class DNSLookupService {

    public static final int DEFAULT_DNS_PORT = 53;
    private static final int MAX_INDIRECTION_LEVEL_NS = 10;
    private static final int MAX_QUERY_ATTEMPTS = 3;
    private static final int MAX_DNS_MESSAGE_LENGTH = 512;
    private static final int SO_TIMEOUT = 5000;

    private final DNSCache cache = DNSCache.getInstance();
    private final Random random = new Random();
    private final DNSVerbosePrinter verbose;
    private final DatagramSocket socket;

    /**
     * Creates a new lookup service. Also initializes the datagram socket object with a default timeout.
     *
     * @param verbose    A DNSVerbosePrinter listener object with methods to be called at key events in the query
     *                   processing.
     * @throws SocketException      If a DatagramSocket cannot be created.
     * @throws UnknownHostException If the nameserver is not a valid server.
     */
    public DNSLookupService(DNSVerbosePrinter verbose) throws SocketException, UnknownHostException {
        this.verbose = verbose;
        socket = new DatagramSocket();
        socket.setSoTimeout(SO_TIMEOUT);
    }

    /**
     * Closes the lookup service and related sockets and resources.
     */
    public void close() {
        socket.close();
    }

    /**
     * Examines a set of resource records to see if any of them are an answer to the given question.
     *
     * @param rrs       The set of resource records to be examined
     * @param question  The DNS question
     * @return          true if the collection of resource records contains an answer to the given question.
     */
    private boolean containsAnswer(Collection<ResourceRecord> rrs, DNSQuestion question) {
        for (ResourceRecord rr : rrs) {
            if (rr.getQuestion().equals(question) && rr.getRecordType() == question.getRecordType()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds all the results for a specific question. If there are valid (not expired) results in the cache, uses these
     * results, otherwise queries the nameserver for new records. If there are CNAME records associated to the question,
     * they are retrieved recursively for new records of the same type, and the returning set will contain both the
     * CNAME record and the resulting resource records of the indicated type.
     *
     * @param question             Host and record type to be used for search.
     * @param maxIndirectionLevels Number of CNAME indirection levels to support.
     * @return A set of resource records corresponding to the specific query requested.
     * @throws DNSErrorException If the number CNAME redirection levels exceeds the value set in
     *                           maxIndirectionLevels.
     */
    public Collection<ResourceRecord> getResultsFollowingCNames(DNSQuestion question, int maxIndirectionLevels)
            throws DNSErrorException {

        if (maxIndirectionLevels < 0) throw new DNSErrorException("CNAME indirection limit exceeded");

        Collection<ResourceRecord> directResults = iterativeQuery(question);
        if (containsAnswer(directResults, question)) {
            return directResults;
        }

        Set<ResourceRecord> newResults = new HashSet<>();
        for (ResourceRecord record : directResults) {
            newResults.add(record);
            if (record.getRecordType() == RecordType.CNAME) {
                newResults.addAll(getResultsFollowingCNames(
                        new DNSQuestion(record.getTextResult(), question.getRecordType(), question.getRecordClass()),
                        maxIndirectionLevels - 1));
            }
        }
        return newResults;
    }

    /**
     * Answers one question.  If there are valid (not expired) results in the cache, returns these results.
     * Otherwise it chooses the best nameserver to query, retrieves results from that server
     * (using individualQueryProcess which adds all the results to the cache) and repeats until either:
     *   the cache contains an answer to the query, or
     *   the cache contains an answer to the query that is a CNAME record rather than the requested type, or
     *   every "best" nameserver in the cache has already been tried.
     *
     *  @param question Host name and record type/class to be used for the query.
     */
    public Collection<ResourceRecord> iterativeQuery(DNSQuestion question)
            throws DNSErrorException {
        // Get cached results for the given question
        Collection<ResourceRecord> cachedRR = cache.getCachedResults(question);

        // If we have cached results, immediately return them
        if (!cachedRR.isEmpty()) {
            return cachedRR;
        }

        // Keep track of queried servers
        Set<InetAddress> queriedServers = new HashSet<>();

        // If no IP addresses are known for the nameservers, iterate over the best nameservers
        while (cachedRR.isEmpty()) {
            // If no cached results are found, get a list of the best nameservers
            List<ResourceRecord> bestNameservers = cache.getBestNameservers(question);
            // Check nameservers with knownIP
            List<ResourceRecord> knownIP = cache.filterByKnownIPAddress(bestNameservers);

            // If no known IP, resolve with CNAME
            if (knownIP.isEmpty() && !bestNameservers.isEmpty()) {
                for (ResourceRecord rr : bestNameservers) {
                    // Create a new DNS question to resolve its IP address
                    DNSQuestion newQuestion = new DNSQuestion(rr.getTextResult(), RecordType.A, RecordClass.IN);

                    // Resolve this nameserver's IP by CNAMEs.
                    getResultsFollowingCNames(newQuestion, MAX_INDIRECTION_LEVEL_NS);
                    knownIP = cache.filterByKnownIPAddress(bestNameservers);
                    if (!knownIP.isEmpty()) {
                        break;
                    }

                }
                // Get the next nameserver from the list and remove it from the list
//                ResourceRecord current = bestNameservers.remove(0);

                // Update knownIP then go next iteration for checking
            }
//            else if (knownIP.isEmpty()) {
//                // Stop while loop if no IP found and best server is empty
//                break;
//            }

            // Perform query on known IP
            ResourceRecord bestServer = knownIP.remove(0);
            if (!queriedServers.contains(bestServer.getInetResult())) {
                individualQueryProcess(question, bestServer.getInetResult());
                queriedServers.add(bestServer.getInetResult());

                // Check cached results
                cachedRR = cache.getCachedResults(question);
                if (!cachedRR.isEmpty()) {
                    return cachedRR;
                }
            }

//            // Update best name server
//            if (knownIP.isEmpty()) {
//                bestNameservers = cache.getBestNameservers(question);
//            }
        }
        // return an empty result.
        return cachedRR;
    }

    private DNSMessage sendQueryTCP(byte[] message, InetAddress server) {
        try (Socket TCPsockt = new Socket(server, DEFAULT_DNS_PORT);
             DataOutputStream outputStream = new DataOutputStream(TCPsockt.getOutputStream());
             DataInputStream inputStream = new DataInputStream(TCPsockt.getInputStream())){

            // Send message
            outputStream.writeShort(message.length);
            outputStream.write(message);
            outputStream.flush();

            // Read the response message
            int length = inputStream.readUnsignedShort();
            byte[] responseBytes = new byte[length];
            inputStream.readFully(responseBytes);

            return new DNSMessage(responseBytes, responseBytes.length);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Handles the process of sending an individual DNS query with a single question. Builds and sends the query (request)
     * message, then receives and parses the response. Received responses that do not match the requested transaction ID
     * are ignored. If no response is received after SO_TIMEOUT milliseconds, the request is sent again, with the same
     * transaction ID. The query should be sent at most MAX_QUERY_ATTEMPTS times, after which the function should return
     * without changing any values. If a response is received, all of its records are added to the cache.
     * <p>
     * If the reply contains a non-zero Rcode value, then throw a DNSErrorException.
     * <p>
     * The method verbose.printQueryToSend() must be called every time a new query message is about to be sent.
     *
     * @param question Host name and record type/class to be used for the query.
     * @param server   Address of the server to be used for the query.
     * @return If no response is received, returns null. Otherwise, returns a set of all resource records
     * received in the response.
     * @throws DNSErrorException if the Rcode in the response is non-zero
     */
    public Set<ResourceRecord> individualQueryProcess(DNSQuestion question, InetAddress server)
            throws DNSErrorException {
        // Build a query message
        DNSMessage queryMessage = buildQuery(question);
        int transactionID = queryMessage.getID();

        // Initialize send message in byte array
        byte[] sendMessage = queryMessage.getUsed();

        // Wrap the message with DatagarmPacket
        DatagramPacket sendPacket = new DatagramPacket(sendMessage, sendMessage.length, server, DEFAULT_DNS_PORT);

        int i = 0;
        // Try to send query with at most MAX_QUERY_ATTEMPTS
        while (i < MAX_QUERY_ATTEMPTS) {
            try {
                // Print specific query before it is sent to the server
                verbose.printQueryToSend("UDP", question, server, queryMessage.getID());

                // Send message through socket
                socket.send(sendPacket);

                // Init a packet to receive message
                byte[] receiveMessage = new byte[MAX_DNS_MESSAGE_LENGTH];
                DatagramPacket receivePacket = new DatagramPacket(receiveMessage, receiveMessage.length);

                // Receive message
                socket.receive(receivePacket);

                // Transfer response packet to message
                DNSMessage responseMessage = new DNSMessage(receivePacket.getData(), receivePacket.getLength());

                if (responseMessage.getQR() && responseMessage.getID() == transactionID) {
                    // If the message was truncated, resend through TCP
                    if (responseMessage.getTC()) {
                        responseMessage = sendQueryTCP(sendMessage, server);
                    }
                    return processResponse(responseMessage);
                } else {
                    i++;
                }
            } catch (IOException e) {
                i++;
                // Handle the timeout exception
                System.out.println("Attempt " + (i + 1) + ": No response after " + SO_TIMEOUT + " milliseconds.");
            }

        }

        System.out.println("Failed after " + MAX_QUERY_ATTEMPTS + " attempts.");
        return null;
    }

    /**
     * Creates a DNSMessage containing a DNS query.
     * A random transaction ID must be generated and filled in the corresponding part of the query. The query
     * must be built as an iterative (non-recursive) request for a regular query with a single question. When the
     * function returns, the message's buffer's position (`message.buffer.position`) must be equivalent
     * to the size of the query data.
     *
     * @param question    Host name and record type/class to be used for the query.
     * @return The DNSMessage containing the query.
     */
    public DNSMessage buildQuery(DNSQuestion question) {
        // Init message
        short randomID = (short)random.nextInt();
        DNSMessage message = new DNSMessage(randomID);

        // Set query, pass in false representing that the message is a query
        message.setQR(false);
        // Set RD, pass in false representing recursion is not desired
        message.setRD(false);

        // add question to the message
        message.addQuestion(question);

        return message;
    }

    /**
     * Parses and processes a response received by a nameserver.
     * If the reply contains a non-zero Rcode value, then throw a DNSErrorException.
     * Adds all resource records found in the response message to the cache.
     * Calls methods in the verbose object at appropriate points of the processing sequence. Must be able
     * to properly parse records of the types: A, AAAA, NS, CNAME and MX (the priority field for MX may be ignored). Any
     * other unsupported record type must create a record object with the data represented as a hex string (see method
     * byteArrayToHexString).
     *
     * @param message The DNSMessage received from the server.
     * @return A set of all resource records received in the response.
     * @throws DNSErrorException if the Rcode value in the reply header is non-zero
     */
    public Set<ResourceRecord> processResponse(DNSMessage message) throws DNSErrorException {
        // Throw a DNSErrorException when rcode is non-zero
        if (message.getRcode() != 0) {
            throw new DNSErrorException("Error code: " + message.getRcode() + ": " + DNSMessage.dnsErrorMessage(message.getRcode()));
        }

        // Init a set to store all resource record received in the response
        Set<ResourceRecord> resourceRecords = new HashSet<>();

        // Print response header by calling function in verbose
        verbose.printResponseHeaderInfo(message.getID(), message.getAA(), message.getTC(), message.getRcode());

        // Get number of resource records from answer section, name server records section, and additional records section
        int[] counts = {message.getANCount(), message.getNSCount(), message.getARCount()};
        DNSQuestion question = message.getQuestion();
        // Loop through the message sections to get resource records
        for (int i = 0; i < counts.length; i++) {
            switch (i) {
                case 0:
                    // Print Answers section header
                    verbose.printAnswersHeader(counts[i]);
                    break;
                case 1:
                    // Print name server header
                    verbose.printNameserversHeader(counts[i]);
                    break;
                case 2:
                    // Print additional section header
                    verbose.printAdditionalInfoHeader(counts[i]);
                    break;
            }

            int numRecords = counts[i];
            while (numRecords > 0) {
                ResourceRecord resourceRecord = message.getRR();

                // Print individual resource record
                verbose.printIndividualResourceRecord(resourceRecord, resourceRecord.getRecordType().getCode(), resourceRecord.getRecordClass().getCode());

                // Add resource records to cache
                cache.addResult(resourceRecord);

                // Add resource records to set
                resourceRecords.add(resourceRecord);

                numRecords--;
            }
        }

        return resourceRecords;
    }

    public static class DNSErrorException extends Exception {
        public DNSErrorException(String msg) {
            super(msg);
        }
    }
}
