package ca.ubc.cs.cs317.dnslookup;

import java.util.Collections;
import java.util.List;

public class ExpectedQuery {
    DNSQuestion question;
    List<ResourceRecord> answers;
    List<ResourceRecord> nameservers;
    List<ResourceRecord> additional;
    static final List<ResourceRecord> empty = Collections.emptyList();

    public ExpectedQuery(DNSQuestion question, List<ResourceRecord> answers) {
        this(question, answers, empty, empty);
    }

    public ExpectedQuery(DNSQuestion question, List<ResourceRecord> answers,
                         List<ResourceRecord> nameservers) {
        this(question, answers, nameservers, empty);
    }

    public ExpectedQuery(DNSQuestion question, List<ResourceRecord> answers,
                         List<ResourceRecord> nameservers, List<ResourceRecord> additional) {
        this.question = question;
        this.answers = answers;
        this.nameservers = nameservers;
        this.additional = additional;
    }
}
