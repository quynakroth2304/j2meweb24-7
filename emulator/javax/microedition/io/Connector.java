package javax.microedition.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Connector {
    public static final int READ = 1;
    public static final int WRITE = 2;
    public static final int READ_WRITE = 3;

    private static final Set<Socket> ACTIVE_SOCKETS = ConcurrentHashMap.newKeySet();

    // Native methods đăng ký qua cheerpjInit({ natives: KeepAliveEngine.getCheerpJNatives() }) khi chạy trên Trình duyệt
    public static native int jsSocketOpen(String host, int port);
    public static native int jsSocketRead(int connId, byte[] buf, int off, int len);
    public static native int jsSocketWrite(int connId, byte[] data, int off, int len);
    public static native void jsSocketClose(int connId);

    public static void closeAllTenantSockets() {
        for (Socket s : ACTIVE_SOCKETS) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        ACTIVE_SOCKETS.clear();
    }

    public static boolean isClientWebSocketMode() {
        return "true".equalsIgnoreCase(System.getProperty("cloud.client.ws", "false"));
    }

    public static Connection open(String name) throws IOException {
        return open(name, READ_WRITE, false);
    }

    public static Connection open(String name, int mode) throws IOException {
        return open(name, mode, false);
    }

    public static Connection open(String name, int mode, boolean timeouts) throws IOException {
        if (name == null) {
            throw new IllegalArgumentException("URL không được null");
        }
        if (name.startsWith("socket://")) {
            String target = name.substring("socket://".length());
            int semiIdx = target.indexOf(';');
            if (semiIdx >= 0) {
                target = target.substring(0, semiIdx);
            }
            int colonIdx = target.lastIndexOf(':');
            if (colonIdx <= 0) {
                throw new IllegalArgumentException("URL socket không hợp lệ: " + name);
            }
            String host = target.substring(0, colonIdx);
            int port = Integer.parseInt(target.substring(colonIdx + 1));

            if (isClientWebSocketMode()) {
                return new WebSocketRelayConnection(host, port);
            }
            return new RealSocketConnection(host, port);
        }
        if (name.startsWith("http://") || name.startsWith("https://")) {
            String targetHttpUrl = name;
            if (isClientWebSocketMode()) {
                String proxyBase = System.getProperty("cloud.net.proxy", "/api/net-proxy?url=");
                targetHttpUrl = proxyBase + URLEncoder.encode(name, "UTF-8");
            }
            return new ProxyHttpConnection(targetHttpUrl);
        }
        throw new ConnectionNotFoundException("Giao thức chưa hỗ trợ: " + name);
    }

    public static DataInputStream openDataInputStream(String name) throws IOException {
        InputConnection conn = (InputConnection) open(name, READ);
        try {
            return conn.openDataInputStream();
        } finally {
            conn.close();
        }
    }

    public static DataOutputStream openDataOutputStream(String name) throws IOException {
        OutputConnection conn = (OutputConnection) open(name, WRITE);
        try {
            return conn.openDataOutputStream();
        } finally {
            conn.close();
        }
    }

    public static InputStream openInputStream(String name) throws IOException {
        return openDataInputStream(name);
    }

    public static OutputStream openOutputStream(String name) throws IOException {
        return openDataOutputStream(name);
    }

    private static class ProxyHttpConnection implements InputConnection, OutputConnection {
        private final HttpURLConnection conn;

        ProxyHttpConnection(String urlStr) throws IOException {
            URL url = new URL(urlStr);
            this.conn = (HttpURLConnection) url.openConnection();
            this.conn.setConnectTimeout(10000);
            this.conn.setReadTimeout(10000);
            this.conn.setRequestProperty("User-Agent", "Profile/MIDP-2.0 Configuration/CLDC-1.1");
            this.conn.setDoInput(true);
        }

        @Override
        public InputStream openInputStream() {
            try {
                return this.conn.getInputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public DataInputStream openDataInputStream() {
            return new DataInputStream(openInputStream());
        }

        @Override
        public OutputStream openOutputStream() {
            try {
                this.conn.setDoOutput(true);
                return this.conn.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public DataOutputStream openDataOutputStream() {
            return new DataOutputStream(openOutputStream());
        }

        @Override
        public void close() {
            this.conn.disconnect();
        }
    }

    private static class RealSocketConnection implements SocketConnection {
        private final Socket socket;

        RealSocketConnection(String host, int port) throws IOException {
            this.socket = new Socket();
            this.socket.setTcpNoDelay(true);
            this.socket.setKeepAlive(true);
            this.socket.connect(new InetSocketAddress(host, port), 15000);
            ACTIVE_SOCKETS.add(this.socket);
        }

        @Override
        public String getAddress() {
            return socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "";
        }

        @Override
        public int getLocalPort() {
            return socket.getLocalPort();
        }

        @Override
        public String getLocalAddress() {
            return socket.getLocalAddress() != null ? socket.getLocalAddress().getHostAddress() : "127.0.0.1";
        }

        @Override
        public int getPort() {
            return socket.getPort();
        }

        @Override
        public int getSocketOption(byte option) {
            return 0;
        }

        @Override
        public void setSocketOption(byte option, int value) {
        }

        @Override
        public InputStream openInputStream() {
            try {
                return socket.getInputStream();
            } catch (IOException e) {
                throw new RuntimeException("Lỗi mở InputStream từ socket", e);
            }
        }

        @Override
        public DataInputStream openDataInputStream() {
            return new DataInputStream(openInputStream());
        }

        @Override
        public OutputStream openOutputStream() {
            try {
                return socket.getOutputStream();
            } catch (IOException e) {
                throw new RuntimeException("Lỗi mở OutputStream từ socket", e);
            }
        }

        @Override
        public DataOutputStream openDataOutputStream() {
            return new DataOutputStream(openOutputStream());
        }

        @Override
        public void close() {
            ACTIVE_SOCKETS.remove(this.socket);
            try {
                this.socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static class WebSocketRelayConnection implements SocketConnection {
        private final String host;
        private final int port;
        private final int connId;
        private volatile boolean closed = false;

        WebSocketRelayConnection(String host, int port) throws IOException {
            this.host = host;
            this.port = port;
            this.connId = jsSocketOpen(host, port);
            if (this.connId <= 0) {
                throw new IOException("Không thể mở kết nối WebSocket Relay tới " + host + ":" + port);
            }
        }

        @Override
        public String getAddress() {
            return host;
        }

        @Override
        public int getLocalPort() {
            return 0;
        }

        @Override
        public String getLocalAddress() {
            return "127.0.0.1";
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public int getSocketOption(byte option) {
            return 0;
        }

        @Override
        public void setSocketOption(byte option, int value) {
        }

        @Override
        public InputStream openInputStream() {
            return new InputStream() {
                private final byte[] singleByte = new byte[1];

                @Override
                public int read() throws IOException {
                    int n = read(singleByte, 0, 1);
                    if (n <= 0) return -1;
                    return singleByte[0] & 0xFF;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (closed) return -1;
                    while (!closed) {
                        int n = jsSocketRead(connId, b, off, len);
                        if (n > 0) return n;
                        if (n < 0) return -1;
                        try {
                            Thread.sleep(8);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted waiting for WebSocket Relay data", e);
                        }
                    }
                    return -1;
                }
            };
        }

        @Override
        public DataInputStream openDataInputStream() {
            return new DataInputStream(openInputStream());
        }

        @Override
        public OutputStream openOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (closed) throw new IOException("WebSocketRelayConnection đã đóng");
                    int sent = jsSocketWrite(connId, b, off, len);
                    if (sent < 0) {
                        throw new IOException("Lỗi ghi dữ liệu qua WebSocket Relay");
                    }
                }
            };
        }

        @Override
        public DataOutputStream openDataOutputStream() {
            return new DataOutputStream(openOutputStream());
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    jsSocketClose(connId);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}