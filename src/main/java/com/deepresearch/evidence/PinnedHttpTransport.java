package com.deepresearch.evidence;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Direct sockets, no proxy or second DNS resolution. Hostname TLS verification stays enabled. */
public final class PinnedHttpTransport implements SafeWebReader.Transport {
    @Override public SafeWebReader.Response fetch(URI uri, List<InetAddress> addresses, Duration remaining, int maxBytes) throws Exception {
        long deadline = System.nanoTime() + remaining.toNanos();
        InetAddress address = addresses.get(0);
        int port = "https".equals(uri.getScheme()) ? 443 : 80;
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(address, port), timeout(deadline));
            Socket connection = plain;
            if ("https".equals(uri.getScheme())) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(plain, SafeWebReader.host(uri), port, true);
                var parameters = tls.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!SafeWebReader.host(uri).contains(":") && !SafeWebReader.host(uri).matches("[0-9.]+"))
                    parameters.setServerNames(List.of(new SNIHostName(SafeWebReader.host(uri))));
                tls.setSSLParameters(parameters); tls.setSoTimeout(timeout(deadline)); tls.startHandshake(); connection = tls;
            }
            try (Socket socket = connection) {
                String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                String request = "GET " + path + " HTTP/1.1\r\nHost: " + uri.getHost()
                        + "\r\nUser-Agent: DeepResearch-SourceReader/1\r\nAccept: text/html, text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                return parse(socket.getInputStream(), socket, deadline, maxBytes, socket.getInetAddress());
            }
        } finally { plain.close(); }
    }

    static int timeout(long deadline) {
        long ms = (deadline - System.nanoTime()) / 1_000_000;
        if (ms <= 0) throw new EvidenceException("SOURCE_TIMEOUT");
        return (int) Math.min(ms, Integer.MAX_VALUE);
    }
    static SafeWebReader.Response parse(InputStream in, Socket socket, long deadline, int maxBytes, InetAddress peer) throws Exception {
        String status = line(in, socket, deadline, 1024);
        if (!status.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
        int code = Integer.parseInt(status.substring(9, 12));
        Map<String, String> headers = new LinkedHashMap<>();
        int total = 0;
        for (int count = 0; ; count++) {
            String line = line(in, socket, deadline, 8192); total += line.length() + 2;
            if (total > 16384 || count > 100) throw new EvidenceException("SOURCE_HEADERS_TOO_LARGE");
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0 || !line.substring(0, colon).matches("[A-Za-z0-9-]+")) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
            String key = line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), value = line.substring(colon + 1).trim();
            if (headers.putIfAbsent(key, value) != null) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
        }
        if (headers.containsKey("transfer-encoding") && headers.containsKey("content-length")) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
        if (List.of(301, 302, 303, 307, 308).contains(code)) return new SafeWebReader.Response(code, headers, new byte[0], peer);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (headers.containsKey("transfer-encoding")) {
            if (!"chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) throw new EvidenceException("SOURCE_ENCODING_UNSUPPORTED");
            for (;;) {
                String chunk = line(in, socket, deadline, 256).split(";", 2)[0];
                if (!chunk.matches("[a-fA-F0-9]{1,8}")) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
                long size = Long.parseLong(chunk, 16);
                if (size > maxBytes - body.size()) throw new EvidenceException("SOURCE_TOO_LARGE");
                if (size == 0) break; // trailers are not trusted or needed; connection closes
                copy(in, socket, deadline, body, (int) size);
                if (!line(in, socket, deadline, 2).isEmpty()) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
            }
        } else if (headers.containsKey("content-length")) {
            String declared = headers.get("content-length");
            if (!declared.matches("[0-9]{1,10}")) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
            long size = Long.parseLong(declared);
            if (size > maxBytes) throw new EvidenceException("SOURCE_TOO_LARGE");
            copy(in, socket, deadline, body, (int) size);
        } else {
            byte[] buffer = new byte[8192];
            for (;;) {
                socket.setSoTimeout(timeout(deadline)); int count = in.read(buffer);
                if (count == -1) break;
                if (body.size() + count > maxBytes) throw new EvidenceException("SOURCE_TOO_LARGE");
                body.write(buffer, 0, count);
            }
        }
        return new SafeWebReader.Response(code, headers, body.toByteArray(), peer);
    }
    static String line(InputStream in, Socket socket, long deadline, int max) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (;;) {
            socket.setSoTimeout(timeout(deadline)); int c = in.read();
            if (c < 0 || bytes.size() > max) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
            if (c == '\n') {
                byte[] all = bytes.toByteArray();
                if (all.length == 0 || all[all.length - 1] != '\r') throw new EvidenceException("SOURCE_HTTP_MALFORMED");
                return new String(all, 0, all.length - 1, StandardCharsets.US_ASCII);
            }
            if (c != '\r' && (c < 32 || c > 126)) throw new EvidenceException("SOURCE_HTTP_MALFORMED");
            bytes.write(c);
        }
    }
    static void copy(InputStream in, Socket socket, long deadline, ByteArrayOutputStream out, int size) throws Exception {
        byte[] buffer = new byte[8192];
        while (size > 0) {
            socket.setSoTimeout(timeout(deadline)); int count = in.read(buffer, 0, Math.min(size, buffer.length));
            if (count < 0) throw new EvidenceException("SOURCE_BODY_INCOMPLETE");
            out.write(buffer, 0, count); size -= count;
        }
    }
}
