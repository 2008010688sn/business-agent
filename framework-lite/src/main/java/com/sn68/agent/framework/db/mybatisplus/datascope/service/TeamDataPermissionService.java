package com.sn68.agent.framework.db.mybatisplus.datascope.service;

import com.sn68.agent.framework.commons.security.DataRefType;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 团队数据权限扩展点.
 *
 * @author Levin
 */
public interface TeamDataPermissionService {

    /**
     * 根据团队ID列表获取团队可见的数据权限.
     *
     * @param teamIds 团队ID列表
     * @return 数据权限映射
     */
    Map<DataRefType, List<Object>> findTeamDataPermissionMap(Collection<String> teamIds);
}
