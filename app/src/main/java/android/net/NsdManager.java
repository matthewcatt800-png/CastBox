package android.net;

// Compile-time API stub; the real framework class wins at runtime.
public class NsdManager {
    public static final int PROTOCOL_DNS_SD = 1;

    public interface DiscoveryListener {
        void onDiscoveryStarted(String serviceType);
        void onStartDiscoveryFailed(String serviceType, int errorCode);
        void onServiceFound(NsdServiceInfo serviceInfo);
        void onServiceLost(NsdServiceInfo serviceInfo);
        void onDiscoveryStopped(String serviceType);
        void onStopDiscoveryFailed(String serviceType, int errorCode);
    }

    public interface ResolveListener {
        void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceResolved(NsdServiceInfo serviceInfo);
    }

    public interface RegistrationListener {
        void onServiceRegistered(NsdServiceInfo serviceInfo);
        void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
        void onServiceUnregistered(NsdServiceInfo serviceInfo);
        void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode);
    }

    public void registerService(NsdServiceInfo service, int protocol, RegistrationListener listener) { throw new RuntimeException("stub"); }
    public void unregisterService(RegistrationListener listener) { throw new RuntimeException("stub"); }
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) { throw new RuntimeException("stub"); }
    public void discoverServices(String serviceType, int protocol, DiscoveryListener listener) { throw new RuntimeException("stub"); }
    public void stopServiceDiscovery(DiscoveryListener listener) { throw new RuntimeException("stub"); }
}
