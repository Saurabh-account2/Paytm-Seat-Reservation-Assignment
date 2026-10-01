package com.paytm.seatreservation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.seatreservation.dto.ErrorResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Extracts user identity from the Authorization header.
 * Token format: "Bearer <user-id>" for users, "Bearer <admin-token>" for admin.
 * Identity comes from the token, never from the request body — this prevents
 * spoofing: a user can only ever act as their token's identity.
 */
@Component
@Order(2)
public class AuthFilter implements Filter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
            "/actuator/prometheus", "/actuator/info");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${app.admin-token}")
    private String adminToken;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();

        // Public endpoints — no auth
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            chain.doFilter(req, res);
            return;
        }

        // GET /shows/{id} is public
        if ("GET".equals(request.getMethod()) && path.matches("/shows/.*")) {
            chain.doFilter(req, res);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendError(response, 401, "Missing or invalid Authorization header");
            return;
        }

        String token = authHeader.substring(7).trim();

        // POST /shows — admin only
        if ("POST".equals(request.getMethod()) && "/shows".equals(path)) {
            if (!adminToken.equals(token)) {
                sendError(response, 403, "Admin access required");
                return;
            }
            request.setAttribute("userId", "admin");
            chain.doFilter(req, res);
            return;
        }

        // All other endpoints — user auth: token IS the user identity
        if (token.isBlank()) {
            sendError(response, 401, "Invalid token");
            return;
        }

        request.setAttribute("userId", token);
        chain.doFilter(req, res);
    }

    private void sendError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        MAPPER.writeValue(response.getOutputStream(),
                new ErrorResponse("auth_error", message));
    }
}
