package com.vy.sales.platform.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  @Value("${security.jwt.secret}")
  private String secret;

  @Value("${security.jwt.access-token-expiry-miliseconds}")
  private long accessTokenExpirationTime;

  @Value("${security.jwt.refresh-token-expiry-seconds}")
  private long refreshTokenExpirationTime;

  private Key key;

  /**
   * Caffeine cache for parsed JWT claims.
   *
   * <p>Every request to /verify triggers HMAC-SHA signature verification, which is CPU-intensive.
   * Caching the parsed {@link Claims} for 30 seconds dramatically cuts CPU usage under concurrent
   * load. TTL is kept short so that the cache never serves claims after a token's expiry changes
   * significantly — in practice tokens live hours, so 30s is safe.
   */
  private Cache<String, Claims> claimsCache;

  @PostConstruct
  public void init() {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.claimsCache =
        Caffeine.newBuilder().maximumSize(5_000).expireAfterWrite(30, TimeUnit.SECONDS).build();
  }

  public Claims getAllClaimsFromToken(String token) {
    return claimsCache.get(
        token,
        t -> {
          try {
            return Jwts.parser()
                .verifyWith((SecretKey) this.key)
                .build()
                .parseClaimsJws(t)
                .getBody();
          } catch (Exception e) {
            return null; // null stored in cache is silently dropped by Caffeine
          }
        });
  }

  public String generateToken(
      String username, Long expirationTime, List<String> roles, Long userId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("roles", roles);
    claims.put("userId", userId);
    final Date createdDate = new Date();
    final Date expirationDate = new Date(System.currentTimeMillis() + expirationTime);

    return Jwts.builder()
        .claims()
        .add(claims)
        .subject(username)
        .issuedAt(createdDate)
        .expiration(expirationDate)
        .and() // Ends the claims() building stage and returns to the main builder
        .signWith(key)
        .compact();
  }

  public String generateAccessToken(String username, Long userId, List<String> roles) {
    return generateToken(username, accessTokenExpirationTime, roles, userId);
  }

  public String generateRefreshToken(String username, Long userId, List<String> roles) {
    return generateToken(username, refreshTokenExpirationTime, roles, userId);
  }

  public boolean isTokenExpired(String token) {
    final Date expiration = getAllClaimsFromToken(token).getExpiration();
    return expiration.before(new Date());
  }

  public String extractUsername(String token) {
    return getAllClaimsFromToken(token).getSubject();
  }

  public boolean validateToken(String token) {
    Claims claims = getAllClaimsFromToken(token);
    if (claims == null) return false;
    return !claims.getExpiration().before(new Date());
  }

  public List<String> extractRoles(String token) {
    try {
      Claims claims = getAllClaimsFromToken(token);
      Object rolesObj = claims.get("roles");
      if (rolesObj instanceof List<?>) {
        List<?> rawList = (List<?>) rolesObj;
        List<String> roles = new ArrayList<>();
        for (Object r : rawList) {
          roles.add(r.toString());
        }
        return roles;
      } else {
        return new ArrayList<>();
      }
    } catch (SignatureException e) {
      return new ArrayList<>();
    }
  }

  public Long extractUserId(String token) {
    Claims claims = getAllClaimsFromToken(token);
    if (claims == null || claims.get("userId") == null) return null;
    return Long.parseLong(claims.get("userId").toString());
  }

  /** Evicts a specific token from the Caffeine claims cache. Used during force-logout. */
  public void invalidate(String token) {
    claimsCache.invalidate(token);
  }

  /** Extracts eventId from the JWT. Returns null if not present (pre-event-selection state). */
  public Long extractEventId(String token) {
    Claims claims = getAllClaimsFromToken(token);
    Object eventId = claims.get("eventId");
    return eventId != null ? Long.parseLong(eventId.toString()) : null;
  }

  /**
   * Generates a token enriched with an eventId claim. Called after the user selects an event
   * context from the frontend.
   */
  public String generateTokenWithEvent(
      String username, Long userId, List<String> roles, Long eventId) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("roles", roles);
    claims.put("userId", userId);
    claims.put("eventId", eventId);
    final Date createdDate = new Date();
    final Date expirationDate = new Date(System.currentTimeMillis() + accessTokenExpirationTime);

    return Jwts.builder()
        .claims()
        .add(claims)
        .subject(username)
        .issuedAt(createdDate)
        .expiration(expirationDate)
        .and()
        .signWith(key)
        .compact();
  }
}
