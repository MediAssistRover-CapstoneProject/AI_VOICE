package com.mediassistrover.backend.security;

import com.mediassistrover.backend.repository.RobotRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component @RequiredArgsConstructor
public class DeviceAuthenticationFilter extends OncePerRequestFilter {
    private final DeviceJwtTokenService tokenService;
    private final RobotRepository robotRepository;
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        String h=request.getHeader("Authorization");
        if(h!=null&&h.startsWith("Bearer ")&&SecurityContextHolder.getContext().getAuthentication()==null){
            try{DeviceJwtClaims c=tokenService.parse(h.substring(7));if(DeviceJwtTokenService.DEVICE_TOKEN_TYPE.equals(c.tokenType()))robotRepository.findById(c.robotId()).filter(r->r.getRobotCode().equals(c.robotCode())).filter(r->r.getDeviceSecretHash()!=null).filter(r->r.getDeviceTokenVersion()==c.tokenVersion()).ifPresent(r->{DevicePrincipal p=new DevicePrincipal(r.getRobotId(),r.getRobotCode(),c.tokenVersion(),c.jti(),c.expiresAt());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(p,null,p.authorities()));});}catch(RuntimeException ignored){SecurityContextHolder.clearContext();}
        }
        chain.doFilter(request,response);
    }
}
