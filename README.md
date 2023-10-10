# DNSLookup
This is the programming assignment of UBC CPSC 317. The purpose of this assignment is to build a DNS client capable of resolving DNS queries of type A, AAAA, MX, NS, and CNAME. The main logic and functionality of the DNS client is found in DNSLookupService.java file.


## Credits
While the DNSLookupService.java file was independently implemented by me, all other supporting files and structures are provided by CPSC 317 staff and the CS department at UBC.

## Setup & Usage
Run DNSLookupCUI.java to start. Instructions from the assignment description:

Once your program is running, the application will interact with the user using console commands. 
- `lookup` hostname (can be abbreviated as l hostname): retrieve the IP address (type A) associated to the name hostname.
- `lookup` hostname type (can be abbreviated as l hostname type): retrieve a response record associated to the name hostname for a specific type.
- `verbose on` (or `verbose off`): turns the verbose tracing mode on (or off).
- `dump`: prints all the records currently in the cache that have not yet expired.
- `reset`: removes all entries from the cache.
- `quit`: close the program.
