package ca.ubc.cs.cs317.dnslookup;

import ca.ubc.cs.cs317.dnslookup.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
public class cwl_exampleDNSTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(2000);
    private static final Duration LONG_TIMEOUT = Duration.ofMillis(7000);
    private static final Duration LONGER_TIMEOUT = Duration.ofMillis(17000);
    private DNSLookupService service;
//    private static final int DNS_TEST_PORT = 55555;
    private static final int TTL = 3600;
    private DNSCache cache;
    private static final String[] testRootServer = {"testRootServer", "127.0.0.1"};
    private DNSLookupCUI verbose;


    @BeforeEach
    public void startServer() throws SocketException, UnknownHostException {
        this.cache = DNSCache.getInstance();
        this.cache.reset(testRootServer);
        this.verbose = new DNSLookupCUI();
        verbose.setVerboseTracing(false);
        this.service = new DNSLookupService(verbose);
    }

    @Test
    @DisplayName("iterativeQuery: single answer in first response")
    public void testIterativeQueryOneAnswer() throws UnknownHostException {
        // Our new Root Server for testing
        String[] testRootServer = {"testRootServer", "127.0.0.1"};

        // Create a Question that the LocalNameServer will receive and an Answer that it will reply with.
        DNSQuestion question = new DNSQuestion("a.host.name.cs.ubc.ca", RecordType.A, RecordClass.IN);
        ResourceRecord response = new ResourceRecord(question, TTL, InetAddress.getByName("123.45.67.89"));
        ExpectedQuery query = new ExpectedQuery(question, Collections.singletonList(response));

        // Start-up the custom Root Server
        LocalNameServer server = new LocalNameServer(DNSCache.stringToInetAddress(testRootServer[1]), query);

        // Populate cache with our custom root server's info
        this.cache.reset(testRootServer);

        // Run query while handling timed out
        try {
            System.out.println();
            this.runIterativeQuery(question);
        } catch (DNSLookupService.DNSErrorException e) {
            Assertions.fail("DNSErrorException thrown");
        }

        // Check results
        List<ResourceRecord> answer = cache.getCachedResults(question);
        Assertions.assertNotNull(answer, "Failed to get an answer");
        Assertions.assertEquals(1, answer.size(), "Incorrect number of answers");
        Assertions.assertEquals(answer.get(0), response);

        // close the root server
        server.shutdown();
    }

    private void runIterativeQuery(DNSQuestion question) throws DNSLookupService.DNSErrorException {
        Assertions.assertTimeoutPreemptively(SHORT_TIMEOUT, () -> this.service.iterativeQuery(question));
    }
}
