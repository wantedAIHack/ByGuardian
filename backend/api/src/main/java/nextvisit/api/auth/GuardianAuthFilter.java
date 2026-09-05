package nextvisit.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.common.ApiError;
import nextvisit.api.common.Json;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** /me/** 에만 적용. 헤더의 토큰을 해시해 보호자·케이스를 찾고 요청 속성에 넣는다. */
@Component
public class GuardianAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Guardian-Token";
    public static final String ATTRIBUTE = "nextvisit.auth";

    private final GuardianRepository guardians;
    private final CaseRepository cases;
    private final TokenService tokens;
    private final Json json;

    public GuardianAuthFilter(GuardianRepository guardians, CaseRepository cases, TokenService tokens, Json json) {
        this.guardians = guardians;
        this.cases = cases;
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/me") || path.startsWith("/me/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            reject(response, "보호자 토큰이 없습니다");
            return;
        }
        Optional<Guardian> guardian = guardians.findByTokenHash(tokens.hash(token.trim()));
        if (guardian.isEmpty()) {
            reject(response, "보호자 토큰이 올바르지 않습니다");
            return;
        }
        Optional<CaseEntity> kase = cases.findById(guardian.get().getCaseId());
        if (kase.isEmpty()) {
            reject(response, "케이스를 찾을 수 없습니다");
            return;
        }
        request.setAttribute(ATTRIBUTE, new AuthContext(guardian.get(), kase.get()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.toJson(new ApiError("UNAUTHORIZED", message)));
    }
}
