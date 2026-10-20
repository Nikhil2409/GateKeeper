package in.code.gatekeeper.management.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

// An IPv4 or IPv6 range like 172.28.0.0/24; matched byte by byte against an address of the same family
public record Cidr(byte[] network, int prefixLength) {

    public static Cidr parse(String value) {
        String[] parts = value.trim().split("/");
        try {
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException("Invalid prefix in " + value);
            }
            return new Cidr(network, prefix);
        } catch (UnknownHostException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid CIDR " + value, e);
        }
    }

    public static List<Cidr> parseAll(List<String> values) {
        return values.stream().filter(value -> !value.isBlank()).map(Cidr::parse).toList();
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }
}
