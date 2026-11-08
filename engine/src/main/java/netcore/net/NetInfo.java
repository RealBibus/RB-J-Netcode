package netcore.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Сетевые мелочи: свои адреса, свободный порт. */
public final class NetInfo {

    private NetInfo() {
    }

    public static final class Address {
        public final String ip;
        public final String iface;

        Address(String ip, String iface) {
            this.ip = ip;
            this.iface = iface;
        }
    }

    /** Все IPv4-адреса машины в локальной сети, сначала «домашние» 192.168/10/172.16. */
    public static List<Address> localAddresses() {
        List<Address> result = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress ia : Collections.list(ni.getInetAddresses())) {
                    if (!(ia instanceof Inet4Address) || ia.isLoopbackAddress()) continue;
                    String ip = ia.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) continue;
                    result.add(new Address(ip, ni.getDisplayName()));
                }
            }
        } catch (Exception ignored) {
        }
        result.sort((a, b) -> Integer.compare(rank(b.ip), rank(a.ip)));
        return result;
    }

    private static int rank(String ip) {
        if (ip.startsWith("192.168.")) return 3;
        if (ip.startsWith("10.")) return 2;
        if (ip.startsWith("172.")) return 2;
        return 1;
    }

    public static String primaryIp() {
        List<Address> list = localAddresses();
        return list.isEmpty() ? "127.0.0.1" : list.get(0).ip;
    }

    /** Свободен ли TCP-порт на этой машине. */
    public static boolean portFree(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Первый свободный порт начиная с {@code from}. */
    public static int freePortFrom(int from) {
        for (int p = from; p < from + 60 && p < 65535; p++) {
            if (portFree(p)) return p;
        }
        return 0;
    }
}
