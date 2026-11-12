package javax.microedition.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.LinkedHashMap;
import java.util.Map;
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
        System.err.println("[Connector] open " + name);
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

    public static class ProxyHttpConnection implements HttpConnection, ContentConnection, StreamConnection, InputConnection, OutputConnection, Connection {
        private final String urlStr;
        private final URL url;
        private HttpURLConnection conn;
        private String method = "GET";
        private final Map<String, String> reqProps = new LinkedHashMap<>();
        private ByteArrayOutputStream postBuf;
        private boolean connected = false;
        private byte[] cachedBody = null;
        private int cachedResponseCode = -1;

        public ProxyHttpConnection(String urlStr) throws IOException {
            this.urlStr = urlStr;
            this.url = new URL(urlStr);
            this.reqProps.put("User-Agent", "Profile/MIDP-2.0 Configuration/CLDC-1.1");
        }

        private synchronized void ensureConnected() throws IOException {
            if (this.connected && this.conn != null) {
                return;
            }
            this.connected = true;
            this.conn = (HttpURLConnection) this.url.openConnection();
            this.conn.setConnectTimeout(10000);
            this.conn.setReadTimeout(10000);
            this.conn.setInstanceFollowRedirects(true);
            this.conn.setRequestMethod(this.method != null ? this.method : "GET");
            for (Map.Entry<String, String> entry : this.reqProps.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    this.conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            this.conn.setDoInput(true);
            if (this.postBuf != null && this.postBuf.size() > 0) {
                this.conn.setDoOutput(true);
                byte[] data = this.postBuf.toByteArray();
                try (OutputStream os = this.conn.getOutputStream()) {
                    os.write(data);
                    os.flush();
                }
            }
            try {
                this.cachedResponseCode = this.conn.getResponseCode();
            } catch (IOException e) {
                this.cachedResponseCode = -1;
                throw e;
            }
            try (InputStream rawIn = (this.cachedResponseCode >= 400)
                    ? this.conn.getErrorStream()
                    : this.conn.getInputStream()) {
                if (rawIn != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = rawIn.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    this.cachedBody = bos.toByteArray();
                } else {
                    this.cachedBody = new byte[0];
                }
            } catch (Throwable ignored) {
                if (this.cachedBody == null) {
                    this.cachedBody = new byte[0];
                }
            }
        }

        @Override
        public InputStream openInputStream() {
            try {
                ensureConnected();
            } catch (IOException ignored) {
            }
            return new ByteArrayInputStream(this.cachedBody != null ? this.cachedBody : new byte[0]);
        }

        @Override
        public DataInputStream openDataInputStream() {
            return new DataInputStream(openInputStream());
        }

        @Override
        public OutputStream openOutputStream() {
            if (this.postBuf == null) {
                this.postBuf = new ByteArrayOutputStream();
            }
            return this.postBuf;
        }

        @Override
        public DataOutputStream openDataOutputStream() {
            return new DataOutputStream(openOutputStream());
        }

        @Override
        public void close() {
            if (this.conn != null) {
                try {
                    this.conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public String getURL() {
            return this.urlStr;
        }

        @Override
        public String getProtocol() {
            return this.url.getProtocol();
        }

        @Override
        public String getHost() {
            return this.url.getHost();
        }

        @Override
        public String getFile() {
            return this.url.getFile();
        }

        @Override
        public String getRef() {
            return this.url.getRef();
        }

        @Override
        public String getQuery() {
            return this.url.getQuery();
        }

        @Override
        public int getPort() {
            int p = this.url.getPort();
            return p != -1 ? p : this.url.getDefaultPort();
        }

        @Override
        public String getRequestMethod() {
            return this.method;
        }

        @Override
        public void setRequestMethod(String method) {
            if (method != null && !method.trim().isEmpty()) {
                this.method = method.trim().toUpperCase();
            }
        }

        @Override
        public String getRequestProperty(String key) {
            return key != null ? this.reqProps.get(key) : null;
        }

        @Override
        public void setRequestProperty(String key, String value) {
            if (key != null && value != null) {
                this.reqProps.put(key, value);
            }
        }

        @Override
        public int getResponseCode() {
            try {
                ensureConnected();
                if (this.cachedResponseCode == 200 && (this.cachedBody == null || this.cachedBody.length == 0)) {
                    return 204;
                }
                return this.cachedResponseCode;
            } catch (Exception e) {
                return -1;
            }
        }

        @Override
        public String getResponseMessage() {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getResponseMessage() : "";
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public long getExpiration() {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getExpiration() : 0L;
            } catch (Exception e) {
                return 0L;
            }
        }

        @Override
        public long getDate() {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getDate() : 0L;
            } catch (Exception e) {
                return 0L;
            }
        }

        @Override
        public long getLastModified() {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getLastModified() : 0L;
            } catch (Exception e) {
                return 0L;
            }
        }

        @Override
        public String getHeaderField(String name) {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getHeaderField(name) : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String getHeaderField(int n) {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getHeaderField(n) : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String getHeaderFieldKey(int n) {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getHeaderFieldKey(n) : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public int getHeaderFieldInt(String name, int def) {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getHeaderFieldInt(name, def) : def;
            } catch (Exception e) {
                return def;
            }
        }

        @Override
        public long getHeaderFieldDate(String name, long def) {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getHeaderFieldDate(name, def) : def;
            } catch (Exception e) {
                return def;
            }
        }

        @Override
        public String getType() {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getContentType() : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public String getEncoding() {
            try {
                ensureConnected();
                return this.conn != null ? this.conn.getContentEncoding() : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public long getLength() {
            try {
                ensureConnected();
                if (this.cachedBody != null && this.cachedBody.length > 0) {
                    return this.cachedBody.length;
                }
                return this.conn != null ? this.conn.getContentLengthLong() : -1L;
            } catch (Exception e) {
                return -1L;
            }
        }
    }

    public static class RealSocketConnection implements SocketConnection {
        private final Socket socket;

        public RealSocketConnection(String host, int port) throws IOException {
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

    public static class WebSocketRelayConnection implements SocketConnection {
        private final String host;
        private final int port;
        private final int connId;
        private volatile boolean closed = false;

        public WebSocketRelayConnection(String host, int port) throws IOException {
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