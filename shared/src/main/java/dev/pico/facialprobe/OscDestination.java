package dev.pico.facialprobe;

/** Numeric unicast IPv4 only: configuration never triggers DNS on the UI thread. */
final class OscDestination {
    static byte[] address(String host) {
        String[] parts = host.trim().split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException("Enter the VRChat PC IPv4 address");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Invalid IPv4 address");
            int n = Integer.parseInt(parts[i]);
            if (n > 255) throw new IllegalArgumentException("Invalid IPv4 address");
            bytes[i] = (byte)n;
        }
        int first = bytes[0] & 255, last = bytes[3] & 255;
        if (first == 0 || first == 127 || first >= 224 || last == 255)
            throw new IllegalArgumentException("Enter a unicast PC address, not loopback or broadcast");
        return bytes;
    }
    static void validate(String host, int port, int hz) {
        address(host);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
        if (hz < 30 || hz > 120) throw new IllegalArgumentException("Send rate must be 30..120 Hz");
    }
}
