package dev.bnacar.distributedratelimiter.security;

import dev.bnacar.distributedratelimiter.config.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IpSecurityServiceTest {

    @Mock
    private SecurityConfiguration securityConfiguration;
    
    private SecurityConfiguration.Ip ipProps;
    private IpSecurityService ipSecurityService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ipProps = new SecurityConfiguration.Ip();
        when(securityConfiguration.getIp()).thenReturn(ipProps);
        
        ipSecurityService = new IpSecurityService(securityConfiguration);
    }

    @Test
    void testIsIpAllowed_WhenBlacklisted_ReturnsFalse() {
        String blacklistedIp = "1.2.3.4";
        ipProps.setBlacklist(Collections.singletonList(blacklistedIp));
        
        assertFalse(ipSecurityService.isIpAllowed(blacklistedIp));
    }

    @Test
    void testIsIpAllowed_WhenWhitelisted_ReturnsTrue() {
        String whitelistedIp = "5.6.7.8";
        ipProps.setWhitelist(Collections.singletonList(whitelistedIp));
        
        assertTrue(ipSecurityService.isIpAllowed(whitelistedIp));
    }

    @Test
    void testIsIpAllowed_WhenWhitelistConfiguredAndIpNotWhitelisted_ReturnsFalse() {
        String randomIp = "9.9.9.9";
        ipProps.setWhitelist(Collections.singletonList("5.6.7.8"));
        
        assertFalse(ipSecurityService.isIpAllowed(randomIp));
    }

    @Test
    void testIsIpAllowed_WhenNoSpecialConfig_ReturnsTrue() {
        assertTrue(ipSecurityService.isIpAllowed("192.168.1.1"));
    }

    @Test
    void testIsIpAllowed_WhenIpBlank_ReturnsFalse() {
        assertFalse(ipSecurityService.isIpAllowed(""));
        assertFalse(ipSecurityService.isIpAllowed(null));
    }
}
