package com.mediassistrover.backend.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component @Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicAssistantRateLimitFilter extends OncePerRequestFilter {
    private final int limit; private final boolean trustForwardedFor; private final Clock clock; private final Map<String,ArrayDeque<Long>> requests=new ConcurrentHashMap<>();
    public PublicAssistantRateLimitFilter(@Value("${app.assistant.public-rate-limit-per-minute:30}") int limit,@Value("${app.assistant.trust-forwarded-for:false}") boolean trustForwardedFor,Clock clock){this.limit=limit;this.trustForwardedFor=trustForwardedFor;this.clock=clock;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/api/v1/public/assistant/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        long now=clock.millis(),cutoff=now-60_000;String key=clientIp(request);ArrayDeque<Long> window=requests.computeIfAbsent(key,k->new ArrayDeque<>());boolean allowed;
        synchronized(window){while(!window.isEmpty()&&window.peekFirst()<cutoff)window.removeFirst();allowed=window.size()<limit;if(allowed)window.addLast(now);}
        if(!allowed){response.setStatus(429);response.setContentType("application/json");response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many public assistant requests\"}");return;}
        if(requests.size()>10_000)requests.entrySet().removeIf(e->{synchronized(e.getValue()){return e.getValue().isEmpty()||e.getValue().peekLast()<cutoff;}});
        chain.doFilter(request,response);
    }
    private String clientIp(HttpServletRequest request){String value=trustForwardedFor?request.getHeader("X-Forwarded-For"):null;if(value==null||value.isBlank())value=request.getRemoteAddr();else value=value.split(",",2)[0].trim();return value==null?"unknown":value;}
}
