package com.sangyunpark99.pgserver.common.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String HEADER = "Idempotency-Key";
    public static final String FINGERPRINT_ATTRIBUTE = "pgserver.idempotency.fingerprint";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (!"POST".equals(request.getMethod()) || key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        HttpServletRequest cached = new CachedBodyRequest(request, body);
        cached.setAttribute(FINGERPRINT_ATTRIBUTE, fingerprint(request.getMethod(), request.getRequestURI(), body));
        chain.doFilter(cached, response);
    }

    static String fingerprint(String method, String uri, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + " " + uri + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
