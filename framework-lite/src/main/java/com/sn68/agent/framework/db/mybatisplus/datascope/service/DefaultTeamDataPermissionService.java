package com.sn68.agent.framework.db.mybatisplus.datascope.service;

import com.sn68.agent.framework.commons.security.DataRefType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 默认团队数据权限实现.
 *
 * @author Levin
 */
public class DefaultTeamDataPermissionService implements TeamDataPermissionService {

    @Override
    public Map<DataRefType, List<Object>> findTeamDataPermissionMap(Collection<String> teamIds) {
        return Map.of();
    }
}
