package com.sn68.agent.framework.boot.security;

import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.ExportEncryptStrategyProvider;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * @author xujp
 * @since 2025/9/17.
 **/
@Component
@RequiredArgsConstructor
public class DefaultEncryptStrategyProvider implements ExportEncryptStrategyProvider {
    private final AuthenticationContext authenticationContext;

    @Override
    public boolean decide() {
        UserInfoDetails userInfoDetails = (UserInfoDetails) authenticationContext.getContext();
        if (userInfoDetails == null) {
            return false;
        }
        Boolean tenantExportEncrypt = userInfoDetails.getTenantExportEncrypt();
        Boolean userExportEncrypt = userInfoDetails.getUserExportEncrypt();
        return Boolean.TRUE.equals(tenantExportEncrypt) && Boolean.TRUE.equals(userExportEncrypt);
    }
}
